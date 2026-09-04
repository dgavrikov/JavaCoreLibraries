package io.github.dgavrikov.core.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SerializationUtils;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

@UtilityClass
@Slf4j
public class SerializeUtils {
    /**
     * Serializes an object into a byte array.
     *
     * @param object The object to be serialized.
     * @return A byte array representing the serialized object, or null in case of an error.
     */
    public static byte @Nullable [] serialize(@Nullable Serializable object) {
        try {
            if (object == null) return null;
            return SerializationUtils.serialize(object);
        } catch (Exception e) {
            log.error("Failed to serialize object.", e);
            return null;
        }
    }

    /**
     * Deserializes a byte array back into an object.
     *
     * @param bytes The byte array.
     * @param clazz The target object class.
     * @param <T>   The object type.
     * @return The deserialized object, or null if mapping fails.
     */
    @Nullable
    public static <T extends Serializable> T deserialize(byte @Nullable [] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            Object obj = SerializationUtils.deserialize(bytes);
            if (clazz.isInstance(obj)) {
                return clazz.cast(obj);
            }
            log.error("Deserialized object is not an instance of the class: {}", clazz.getName());
            return null;
        } catch (Exception e) {
            log.error("Failed to deserialize object.", e);
            return null;
        }
    }

    /**
     * Serializes an object into a byte array and compresses it. Returns null on error.
     *
     * @param object The object to be serialized.
     * @return The compressed byte array.
     */
    public static byte @Nullable [] serializeWithCompress(@Nullable Serializable object) {
        if (object == null) return null;
        var data = serialize(object);
        if (data == null) return null;
        return ByteUtils.compress(data);
    }

    /**
     * Deserializes a compressed byte array back into an object.
     *
     * @param bytes The compressed byte array.
     * @param clazz The target object class.
     * @param <T>   The object type.
     * @return The deserialized object, or null in case of an error.
     */
    @Nullable
    public static <T extends Serializable> T deserializeWithDecompress(byte @Nullable [] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) return null;
        var data = ByteUtils.decompress(bytes);
        if (data == null) return null;
        return deserialize(data, clazz);
    }
}
