package com.github.dgavrikov.core.database.service.impl;

import com.github.dgavrikov.core.database.service.DbFactory;
import com.github.dgavrikov.core.database.service.FetchType;
import com.github.dgavrikov.core.database.service.QuerySpecification;
import jakarta.persistence.*;
import jakarta.persistence.criteria.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class DbFactoryImpl implements DbFactory {
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final Map<Class<?>, Map<Class<?>, Field>> FETCH_FIELD_CACHE = new ConcurrentHashMap<>();

    @Override
    public <T> QueryBuilder<T> select(Class<T> entityClass) {
        return new QueryBuilderImpl<>(entityClass);
    }

    @Override
    public void clear() {
        entityManager.clear();
    }

    @Override
    public void detach(Object entity) {
        if (entity != null)
            entityManager.detach(entity);
    }

    @Override
    @Transactional
    public <T> T saveAndFlush(@NotNull T entity) {
        T saveEntity = entityManager.merge(entity);
        entityManager.flush();
        return saveEntity;
    }

    private class QueryBuilderImpl<T> implements DbFactory.QueryBuilder<T> {
        private final Class<T> entityClass;
        private QuerySpecification<T> specification;
        private Map<String, Object> filters;
        private String hql;
        private String hqlCount;
        private Pageable pageable;
        private LockModeType lockMode;
        private final Map<String, Object> customHints = new HashMap<>();
        private FetchType[] fetchTypes = new FetchType[0];
        private Integer limit;
        private Integer first;
        private Boolean isClear = false;

        private QueryBuilderImpl(Class<T> entityClass) {
            this.entityClass = entityClass;
        }

        @Override
        public QueryBuilder<T> clear() {
            this.isClear = true;
            return this;
        }

        @Override
        public QueryBuilder<T> where(QuerySpecification<T> spec) {
            this.specification = spec;
            return this;
        }

        @Override
        public QueryBuilder<T> hql(String hql, Map<String, Object> filters) {
            this.hql = hql;
            this.filters = filters;
            return this;
        }

        @Override
        public QueryBuilder<T> hqlCount(String hql, Map<String, Object> filters) {
            this.hqlCount = hql;
            this.filters = filters;
            return this;
        }

        @Override
        public QueryBuilder<T> pageable(Pageable p) {
            this.pageable = p;
            return this;
        }

        @Override
        public QueryBuilder<T> limit(int limit) {
            this.limit = limit;
            return this;
        }

        @Override
        public QueryBuilder<T> first(int first) {
            this.first = first;
            return this;
        }

        @Override
        public QueryBuilder<T> lock(LockModeType mode) {
            this.lockMode = mode;
            return this;
        }

        @Override
        public QueryBuilder<T> skipLocked() {
            this.lockMode = LockModeType.PESSIMISTIC_WRITE;
            this.customHints.put("jakarta.persistence.lock.timeout", "-2");
            return this;
        }

        @Override
        public QueryBuilder<T> hint(String name, Object value) {
            this.customHints.put(name, value);
            return this;
        }

        @Override
        public QueryBuilder<T> fetch(FetchType... types) {
            if (types != null && types.length > 0) {
                this.fetchTypes = types;
                EntityGraph<T> graph = createDynamicGraph(entityClass, types);
                this.customHints.put("jakarta.persistence.fetchgraph", graph);
            }
            return this;
        }

        @Override
        public List<T> getResultList() {
            return transactionTemplate.execute(status -> execute());
        }

        @Override
        public Optional<T> getSingleResult() {
            var result = transactionTemplate.execute(status -> {
                this.limit = 1;
                return execute().stream().findFirst().orElse(null);
            });

            if (result == null)
                return Optional.empty();
            return Optional.of(result);
        }

        @Override
        public PageResponse<T> getPage() {
            return transactionTemplate.execute(status -> {
                long total = count();
                if (total == 0)
                    return new PageResponse<>(Collections.emptyList(), 0);

                List<T> content = execute();
                return new PageResponse<>(content, total);
            });
        }

        private List<T> execute() {
            if (isClear)
                entityManager.clear();
            List<T> results;

            if (pageable != null && pageable.isPaged() && hql == null) {
                results = findWithPagination();
            } else if (hql != null) {
                results = findByHql();
            } else {
                results = findByCriteria();
            }

            if (!results.isEmpty() && fetchTypes.length > 0) {
                Set<FetchType> requestedTypes = Set.of(fetchTypes);
                Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                results.forEach(item -> initializeFetchFields(item, requestedTypes, visited));
            }
            return results;
        }

        private List<T> findByHql() {
            var query = entityManager.createQuery(hql, entityClass);
            applyHintSettings(query);
            if (filters != null) filters.forEach(query::setParameter);

            applyPagingSettings(query);
            return query.getResultList();
        }

        private List<T> findByCriteria() {
            var cb = entityManager.getCriteriaBuilder();
            var cq = cb.createQuery(entityClass);
            var root = cq.from(entityClass);
            List<Predicate> predicates = new ArrayList<>();

            if (specification != null) specification.accept(root, cb, predicates);

            cq.select(root).where(cb.and(predicates.toArray(new Predicate[0])));

            var query = entityManager.createQuery(cq);
            applyHintSettings(query);
            applyPagingSettings(query);

            return query.getResultList();
        }

        private List<T> findWithPagination() {
            if (fetchTypes == null || fetchTypes.length == 0)
                return findByCriteria();

            var cb = entityManager.getCriteriaBuilder();
            String idName = entityManager.getMetamodel().entity(entityClass).getId(Object.class).getName();

            // find Id
            var idCq = cb.createQuery(Object.class);
            var idRoot = idCq.from(entityClass);
            List<Predicate> idPredicates = new ArrayList<>();
            if (specification != null) specification.accept(idRoot, cb, idPredicates);

            applySorting(idCq, idRoot, cb);
            idCq.select(idRoot.get(idName)).where(cb.and(idPredicates.toArray(new Predicate[0])));

            var queryId = entityManager.createQuery(idCq);

            applyPagingSettings(queryId);

            List<Object> ids = queryId.getResultList();

            if (ids.isEmpty()) return Collections.emptyList();

            // Main queery
            var mainCq = cb.createQuery(entityClass);
            var mainRoot = mainCq.from(entityClass);
            mainCq.select(mainRoot).where(mainRoot.get(idName).in(ids));
            applySorting(mainCq, mainRoot, cb);

            var query = entityManager.createQuery(mainCq);
            applyHintSettings(query);
            return query.getResultList();
        }

        private void applyHintSettings(TypedQuery<T> query) {
            if (lockMode != null) query.setLockMode(lockMode);
            query.setHint("jakarta.persistence.cache.retrieveMode", CacheRetrieveMode.BYPASS);
            query.setHint("jakarta.persistence.cache.storeMode", CacheStoreMode.REFRESH);
            customHints.forEach(query::setHint);
        }

        private void applyPagingSettings(TypedQuery<?> query) {
            if (pageable != null && pageable.isPaged()) {
                query.setFirstResult((int) pageable.getOffset());
                query.setMaxResults(pageable.getPageSize());
            } else {
                if (limit != null) query.setMaxResults(limit);
                if (first != null) query.setFirstResult(first);
            }
        }

        private long count() {
            if (hqlCount != null) {
                var query = entityManager.createQuery(hqlCount);
                if (filters != null) filters.forEach(query::setParameter);
                return ((Number) query.getSingleResult()).longValue();
            }

            var cb = entityManager.getCriteriaBuilder();
            var countCq = cb.createQuery(Long.class);
            var root = countCq.from(entityClass);

            List<Predicate> predicates = new ArrayList<>();
            if (specification != null)
                specification.accept(root, cb, predicates);

            countCq.select(cb.count(root)).where(cb.and(predicates.toArray(new Predicate[0])));

            return entityManager.createQuery(countCq).getSingleResult();
        }

        private void applySorting(CriteriaQuery<?> cq, Root<T> root, CriteriaBuilder cb) {
            if (pageable.getSort().isSorted()) {
                List<Order> orders = new ArrayList<>();
                pageable.getSort().forEach(o ->
                        orders.add(o.isAscending() ? cb.asc(root.get(o.getProperty())) : cb.desc(root.get(o.getProperty()))));
                cq.orderBy(orders);
            }
        }
    }

    // Auxiliary methods

    private <T> EntityGraph<T> createDynamicGraph(Class<T> entityClass, FetchType... types) {
        EntityGraph<T> graph = entityManager.createEntityGraph(entityClass);
        var fieldMapping = getFetchFieldMapping(entityClass);

        for (var type : types) {
            Field field = fieldMapping.get(type.getEntityClass());
            if (field != null)
                graph.addAttributeNodes(field.getName());
        }

        return graph;
    }

    private Map<Class<?>, Field> getFetchFieldMapping(Class<?> clazz) {
        return FETCH_FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Map<Class<?>, Field> map = new HashMap<>();
            var current = c;
            while (current != null && current != Object.class) {
                for (var field : current.getDeclaredFields()) {
                    var targetEntityClass = getGenericType(field);
                    if (!targetEntityClass.isPrimitive() && targetEntityClass.getName().startsWith("java.lang")) {
                        if (!map.containsKey(targetEntityClass)) {
                            field.setAccessible(true);
                            map.put(targetEntityClass, field);
                        }
                    }
                }
                current = current.getSuperclass();
            }
            return map;
        });
    }

    private Class<?> getGenericType(Field field) {
        if (Collection.class.isAssignableFrom(field.getType())) {
            var genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType pt) {
                var actualType = pt.getActualTypeArguments()[0];
                if (actualType instanceof Class<?> clazz)
                    return clazz;
            }
        }
        return field.getType();
    }

    private void initializeFetchFields(Object entity, Set<FetchType> requestedType, Set<Object> visited) {
        if (entity == null || visited.contains(entity)) return;
        visited.add(entity);

        if (entity instanceof Collection<?> col) {
            if (col.isEmpty()) return;
            Hibernate.initialize(col);
            for (Object item : col)
                initializeFetchFields(item, requestedType, visited);
            return;
        }

        Object unproxied = Hibernate.unproxy(entity);
        Map<Class<?>, Field> fieldMappings = getFetchFieldMapping(unproxied.getClass());

        for (var type : requestedType) {
            var field = fieldMappings.get(type.getEntityClass());
            if (field != null) {
                try {
                    Object fieldValue = field.get(unproxied);
                    if (fieldValue != null) {
                        Hibernate.initialize(fieldValue);
                        initializeFetchFields(fieldValue, requestedType, visited);
                    }
                } catch (IllegalAccessException ignore) {
                }
            }
        }
    }
}
