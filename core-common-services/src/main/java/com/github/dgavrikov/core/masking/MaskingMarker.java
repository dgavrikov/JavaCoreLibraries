package com.github.dgavrikov.core.masking;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class MaskingMarker {
    public static final Marker MASKING_HEADER = MarkerFactory.getMarker("header_masking");
    public static final Marker MASKING_JSON_MARKER = MarkerFactory.getMarker("json_masking");
    public static final Marker MASKING_MARKER = MarkerFactory.getMarker("masking");
    public static final Marker MASKING_OBJECT_MARKER = MarkerFactory.getMarker("object_masking");
    public static final Marker MASKING_JSON_ALL_MARKER = MarkerFactory.getMarker("json_all_masking");
}
