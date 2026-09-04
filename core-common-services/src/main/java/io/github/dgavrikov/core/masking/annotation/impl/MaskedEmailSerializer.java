package io.github.dgavrikov.core.masking.annotation.impl;

import io.github.dgavrikov.core.masking.BaseMasked;
import io.github.dgavrikov.core.masking.pattern.MaskedPatternEmail;

public class MaskedEmailSerializer
        extends BaseMasked {
    public MaskedEmailSerializer() {
        this.replaceFunction = MaskedPatternEmail.masking;
    }
}
