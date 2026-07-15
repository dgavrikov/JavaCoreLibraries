package com.github.dgavrikov.core.utils;

import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;
import org.jetbrains.annotations.Nullable;

@UtilityClass
public class ArrayUtils {
    public static <T> boolean contains(@Nullable T[] array, @Nullable T value){
        if(CollectionUtils.sizeIsEmpty(array)) return false;
        for (T t: array){
            if(t == null) continue;
            if(t.equals(value)) return true;
        }
        return false;
    }
}
