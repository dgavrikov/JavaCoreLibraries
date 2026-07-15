package com.github.dgavrikov.core.database.service;

import jakarta.persistence.LockModeType;
import org.jetbrains.annotations.ApiStatus;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface DbFactory {
    /***
     * Retrieves an entity from the database.
     * @param entityClass the entity class
     * @return QueryBuilder of the current entity for method chaining
     * @param <T> type
     */
    <T> QueryBuilder<T> select(Class<T> entityClass);

    /***
     * Clears the first-level cache.
     */
    void clear();

    /**
     * Detaches the entity from the first-level cache.
     * @param entity the entity
     */
    void detach(Object entity);

    /**
     * Saves and flushes the entity to the database.
     * @param entity the entity
     * @return the saved entity
     * @param <T> type
     */
    <T> T saveAndFlush(T entity);

    interface QueryBuilder<T>{
        record PageResponse<T>(List<T> content, long totalElements){
        }

        /**
         * Clears the first-level cache.
         * @return the current Builder for fluent method chaining
         */
        QueryBuilder<T> clear();

        /**
         * Selection criteria defined as a specification. Multiple conditions can be set using and(), or(), etc.
         * @param spec selection criteria specification
         * @return the current Builder for fluent method chaining
         */
        QueryBuilder<T> where(QuerySpecification<T> spec);

        /**
         * Query defined as HQL.
         * @param hql the query string
         * @param filters query parameters as a key-value map
         * @return the current Builder for fluent method chaining
         */
        QueryBuilder<T> hql(String hql, Map<String, Object> filters);

        /**
         * Retrieves the total count of records when using an HQL query, only triggered if getPage is invoked.
         * @param hqlCount the query string that returns select count(*)
         * @param filters query parameters as a key-value map
         * @return the current Builder for fluent method chaining
         */
        QueryBuilder<T> hqlCount(String hqlCount, Map<String, Object> filters);

        /**
         * Sets properties for pagination.
         * @param p {@link org.springframework.data.domain.PageRequest}
         * @return the current Builder for fluent method chaining
         */
        QueryBuilder<T> pageable(Pageable p);

        /**
         * Sets the maximum number of results to be returned by the query.
         * @param limit the number of records to return
         * @return the current Builder for fluent method chaining
         */
        QueryBuilder<T> limit(int limit);

        /**
         * The number of records to skip before starting the selection. Defaults to 0 if not called.
         * @param first the number of records to skip
         * @return the current Builder for fluent method chaining
         */
        QueryBuilder<T> first(int first);

        /**
         * Specifies the lock mode for entities returned by the query.
         * @param mode the lock mode type
         * @return the current Builder for fluent method chaining
         */
        QueryBuilder<T> lock(LockModeType mode);

        /**
         * Preset lock mode for entities returned by the query.
         * Equivalent to: select * from table t for update skip locked;
         * @return the current Builder for fluent method chaining
         */
        QueryBuilder<T> skipLocked();

        /**
         * Passes custom hints recognized by Hibernate.
         * @param name the hint name
         * @param value the hint value (can be any object)
         * @return the current Builder for fluent method chaining
         */
        QueryBuilder<T> hint(String name, Object value);

        /**
         * Specifies which fields should be fetched eagerly when executing the query.
         * Expects names of entities to build an entity graph.
         * @param types fetch types configuration
         * @return the current Builder for fluent method chaining
         */
        QueryBuilder<T> fetch(FetchType... types);

        /**
         * Returns a list of query results. Returns an empty list if no results are found.
         * @return {@link T} response containing the query results.
         */
        List<T> getResultList();

        /**
         * Returns a single query result.
         * @return {@link Optional<T>} response containing the query result.
         */
        Optional<T> getSingleResult();

        /**
         * Returns pagination data for the query. Returns an empty page if no results are found.
         * @return {@link T} response containing the paginated results.
         */
        PageResponse<T> getPage();
    }
}
