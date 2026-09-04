package io.github.dgavrikov.core.masking.annotation.impl;

import io.github.dgavrikov.core.masking.BaseMasked;
import io.github.dgavrikov.core.masking.pattern.MaskedPatternFio;

public class MaskedFioSerializer
        extends BaseMasked {
    public MaskedFioSerializer() {
        this.replaceFunction = MaskedPatternFio.masking;
    }
}
