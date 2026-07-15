package com.github.dgavrikov.core.masking.annotation.impl;

import com.github.dgavrikov.core.masking.BaseMasked;
import com.github.dgavrikov.core.masking.pattern.MaskedPatternString;

public class MaskedStringSerializer
        extends BaseMasked {
    public MaskedStringSerializer() {
        this.replaceFunction = MaskedPatternString.masking;
    }

}
