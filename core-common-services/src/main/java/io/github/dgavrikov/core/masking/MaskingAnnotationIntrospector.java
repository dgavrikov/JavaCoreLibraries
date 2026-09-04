package io.github.dgavrikov.core.masking;

import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MaskingAnnotationIntrospector  extends JacksonAnnotationIntrospector {

    /* Old implementation
    private final Class<?>[] customAnnotations;

    public MaskingAnnotationIntrospector(Class<?>[] customAnnotations) {
        this.customAnnotations = customAnnotations;
    }

    @Override
    public Object findSerializer(Annotated a) {
        var serializer = super.findSerializer(a);
        if(serializer == null || serializer == JsonSerializer.None.class) {
            for(var annotationClass : customAnnotations) {
                if(a.hasAnnotation(annotationClass)) {
                    JsonSerialize serializeAnno = a.getAnnotation((Class<Annotation>) annotationClass)
                            .annotationType()
                            .getAnnotation(JsonSerialize.class);
                    if(serializeAnno != null && serializeAnno.using() != JsonSerializer.None.class)
                        return serializeAnno.using();
                }
            }
        }
        return serializer;
    }
     */
    // Cache the mapping of "Custom Annotation -> Serializer Class"
    // This eliminates expensive reflection lookups during each field serialization
    private final Map<Class<? extends Annotation>, Class<? extends JsonSerializer>> serializerCache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public MaskingAnnotationIntrospector(Class<?>[] customAnnotations) {
        if (customAnnotations != null) {
            for (Class<?> clazz : customAnnotations) {
                // Проверяем, что переданный класс действительно является аннотацией
                if (clazz.isAnnotation()) {
                    Class<? extends Annotation> annoClazz = (Class<? extends Annotation>) clazz;

                    // Заранее (один раз при старте) вытаскиваем @JsonSerialize из мета-аннотации
                    JsonSerialize jsonSerialize = annoClazz.getAnnotation(JsonSerialize.class);
                    if (jsonSerialize != null && jsonSerialize.using() != JsonSerializer.None.class) {
                        serializerCache.put(annoClazz, jsonSerialize.using());
                    }
                }
            }
        }
    }

    @Override
    public Object findSerializer(Annotated a) {
        Object serializer = super.findSerializer(a);

        // If the standard serializer is not found
        if (serializer == null || serializer == JsonSerializer.None.class) {
            // Simply iterate through the annotations for which we have a masking serializer registered
            for (var entry : serializerCache.entrySet()) {
                if (a.hasAnnotation(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }

        return serializer;
    }
}