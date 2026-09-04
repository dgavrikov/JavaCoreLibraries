package io.github.dgavrikov.core.masking.annotation.impl;

import io.github.dgavrikov.core.masking.BaseMasked;
import io.github.dgavrikov.core.masking.pattern.MaskedPatternIDNumber;

public class MaskedIDNumberSerializer
        extends BaseMasked {
    public MaskedIDNumberSerializer() {
        this.replaceFunction = MaskedPatternIDNumber.masking;
    }
}
