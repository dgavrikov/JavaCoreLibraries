package io.github.dgavrikov.core.database.service.impl;

import io.github.dgavrikov.core.database.service.DbFactory;
import io.github.dgavrikov.core.database.service.FetchType;
import io.github.dgavrikov.core.database.service.QuerySpecification;
import jakarta.persistence.*;
import jakarta.persistence.criteria.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.hibernate.Hibernate;
import org.hibernate.StaleObjectStateException;
import org.hibernate.exception.LockAcquisitionException;
import org.jetbrains.annotations.NotNull;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class DbFactoryImpl implements DbFactory {
    private static final Set<Class<? extends Throwable>> LOCKING_EXCEPTIONS = Set.of(
            StaleObjectStateException.class,
            LockAcquisitionException.class,
            OptimisticLockException.class,
            PessimisticLockingFailureException.class,
            ConcurrencyFailureException.class
    );
    private static final Set<String> DEADLOCK_SQL_STATES = Set.of(
            "40001", // Serialization failure / Deadlock (стандарт SQL)
            "40P01"  // "40P01" for PostgreSQL deadlock
    );

    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final Map<Class<?>, Map<Class<?>, List<Field>>> FETCH_FIELD_CACHE = new ConcurrentHashMap<>();

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

    @Override
    public <T> T clearProxy(T entity) {
        Set<FetchType> requestedTypes = Set.of();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        if (entity instanceof Collection<?> results) {
            if (CollectionUtils.isEmpty(results))
                return entity;
            results.forEach(item -> initializeFetchFields(item, requestedTypes, visited));
        } else {
            initializeFetchFields(entity, requestedTypes, visited);
        }
        return entity;
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
        private String nativeSql;
        private String nativeSqlCount;
        private Map<String, Object> nativeFilters;

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

        @Override
        public QueryBuilder<T> nativeQuery(String nativeSql, Map<String, Object> filters) {
            this.nativeSql = nativeSql;
            this.nativeFilters = filters;
            return this;
        }

        @Override
        public QueryBuilder<T> nativeQueryCount(String nativeSqlCount, Map<String, Object> filters) {
            this.nativeSqlCount = nativeSqlCount;
            this.nativeFilters = filters;
            return this;
        }

        private List<T> execute() {
            if (isClear)
                entityManager.clear();

            int maxRetries = 3;
            int retryCount = 0;
            List<T> results = null;

            while (retryCount < maxRetries) {
                try {
                    if (pageable != null && pageable.isPaged() && hql == null) {
                        results = findWithPagination();
                    } else if (nativeSql != null) {
                        results = findByNativeQuery();
                    } else if (hql != null) {
                        results = findByHql();
                    } else {
                        results = findByCriteria();
                    }

                    if (!results.isEmpty()) {
                        Set<FetchType> requestedTypes = fetchTypes != null && fetchTypes.length > 0
                                ? Set.of(fetchTypes)
                                : Set.of();
                        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                        results.forEach(item -> initializeFetchFields(item, requestedTypes, visited));
                    }
                    return results;
                } catch (RuntimeException e) {
                    retryCount++;
                    if (isLockingException(e)) {
                        if (retryCount >= maxRetries) {
                            throw e;
                        }
                        try {
                            Thread.sleep(100L * retryCount);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Прервано ожидание перед повторной попыткой", e);
                        }
                    } else {
                        throw e;
                    }
                }
            }

            return results;
        }

        private List<T> findByNativeQuery() {
            var query = entityManager.createNamedQuery(nativeSql, entityClass);
            if (nativeFilters != null)
                nativeFilters.forEach(query::setParameter);

            return query.getResultList();
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
            if (nativeSqlCount != null) {
                var query = entityManager.createNativeQuery(nativeSqlCount);
                if (nativeFilters != null)
                    nativeFilters.forEach(query::setParameter);
                return ((Number) query.getSingleResult()).longValue();
            }
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
            List<Field> fields = fieldMapping.get(type.getEntityClass());
            if (CollectionUtils.isNotEmpty(fields))
                fields.forEach(field -> graph.addAttributeNodes(field.getName()));
        }

        return graph;
    }

    private Map<Class<?>, List<Field>> getFetchFieldMapping(Class<?> clazz) {
        var innerMap = FETCH_FIELD_CACHE.computeIfAbsent(clazz, k -> new HashMap<>());

        if (!innerMap.isEmpty())
            return innerMap;

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (var field : current.getDeclaredFields()) {
                var targetEntityClass = getGenericType(field);
                if (!targetEntityClass.isPrimitive()
                        && !targetEntityClass.getName().startsWith("java.lang")
                        && field.isAnnotationPresent(JoinColumn.class)) {
                    innerMap.computeIfAbsent(targetEntityClass, k -> {
                        field.setAccessible(true);
                        return new ArrayList<>();
                    }).add(field);
                }
            }
            current = current.getSuperclass();
        }
        return innerMap;
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
        var fieldMappings = getFetchFieldMapping(unproxied.getClass());

        if(MapUtils.isNotEmpty(fieldMappings)){
            for (var type : requestedType) {
                var fields = fieldMappings.get(type.getEntityClass());
                if (CollectionUtils.isNotEmpty(fields)) {
                    for(var field : fields){
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

        for (var entry : fieldMappings.entrySet()) {
            var targetClass = entry.getKey();
            var fields = entry.getValue();
            var isRequested = requestedType.stream()
                    .anyMatch(ft -> ft.getEntityClass().equals(targetClass));

            if(!isRequested) {
                for(var field : fields) {
                    try{
                        var fieldValue = field.get(unproxied);
                        if(fieldValue != null && !Hibernate.isInitialized(fieldValue)) {
                            if(Collection.class.isAssignableFrom(field.getType())) {
                                field.set(unproxied, Collections.emptyList());
                            } else {
                                field.set(unproxied, null);
                            }
                        }
                    } catch (IllegalAccessException ignore) {

                    }
                }
            }
        }
    }

    private boolean isLockingException(Throwable ex) {
        if (ex == null) return false;

        for(var lockException : LOCKING_EXCEPTIONS){
            if(lockException.isInstance(ex))
                return true;
        }

        if (ex instanceof SQLException sqlEx) {
            return DEADLOCK_SQL_STATES.contains(sqlEx.getSQLState());
        }

        return false;
    }
}
