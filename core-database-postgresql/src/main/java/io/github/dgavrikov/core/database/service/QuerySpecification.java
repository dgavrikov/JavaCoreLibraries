package io.github.dgavrikov.core.database.service;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

@ApiStatus.Experimental
@FunctionalInterface
public interface QuerySpecification<T> {
    void accept(Root<T> root, CriteriaBuilder cb, List<Predicate> predicates);
}
