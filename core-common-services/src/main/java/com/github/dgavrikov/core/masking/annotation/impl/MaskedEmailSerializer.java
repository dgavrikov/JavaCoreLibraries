package com.github.dgavrikov.core.masking.annotation.impl;

import com.github.dgavrikov.core.masking.BaseMasked;
import com.github.dgavrikov.core.masking.pattern.MaskedPatternEmail;

public class MaskedEmailSerializer
        extends BaseMasked {
    public MaskedEmailSerializer() {
        this.replaceFunction = MaskedPatternEmail.masking;
    }
}
