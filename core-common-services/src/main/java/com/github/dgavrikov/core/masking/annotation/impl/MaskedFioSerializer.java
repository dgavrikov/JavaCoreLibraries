package com.github.dgavrikov.core.masking.annotation.impl;

import com.github.dgavrikov.core.masking.BaseMasked;
import com.github.dgavrikov.core.masking.pattern.MaskedPatternFio;

public class MaskedFioSerializer
        extends BaseMasked {
    public MaskedFioSerializer() {
        this.replaceFunction = MaskedPatternFio.masking;
    }
}
