package io.github.dgavrikov.core.masking.annotation.impl;

import io.github.dgavrikov.core.masking.BaseMasked;
import io.github.dgavrikov.core.masking.pattern.MaskedPatternIDSeries;

public class MaskedIDSeriesSerializer
        extends BaseMasked {
    public MaskedIDSeriesSerializer() {
        this.replaceFunction = MaskedPatternIDSeries.masking;
    }
}
