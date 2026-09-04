package io.github.dgavrikov.core.masking.annotation.impl;

import io.github.dgavrikov.core.masking.BaseMasked;
import io.github.dgavrikov.core.masking.pattern.MaskedPatternString;

public class MaskedStringSerializer
        extends BaseMasked {
    public MaskedStringSerializer() {
        this.replaceFunction = MaskedPatternString.masking;
    }

}
