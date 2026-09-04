package io.github.dgavrikov.core.database.utils;

import org.hibernate.Hibernate;
import org.mapstruct.Condition;
import org.springframework.stereotype.Component;

@Component
public class MapperUtils {
    @Condition
    public boolean isInitialized(Object field){
        if(field == null)
            return false;
        return Hibernate.isInitialized(field);
    }
}
