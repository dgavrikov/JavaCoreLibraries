package com.github.dgavrikov.core.masking.annotation.impl;

import com.github.dgavrikov.core.masking.BaseMasked;
import com.github.dgavrikov.core.masking.pattern.MaskedPatternIDSeries;

public class MaskedIDSeriesSerializer
        extends BaseMasked {
    public MaskedIDSeriesSerializer() {
        this.replaceFunction = MaskedPatternIDSeries.masking;
    }
}
