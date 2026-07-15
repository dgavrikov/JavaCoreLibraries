package com.github.dgavrikov.core.masking.annotation.impl;

import com.github.dgavrikov.core.masking.BaseMasked;
import com.github.dgavrikov.core.masking.pattern.MaskedPatternIDNumber;

public class MaskedIDNumberSerializer
        extends BaseMasked {
    public MaskedIDNumberSerializer() {
        this.replaceFunction = MaskedPatternIDNumber.masking;
    }
}
