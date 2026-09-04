package io.github.dgavrikov.core.masking.annotation.impl;

import io.github.dgavrikov.core.masking.BaseMasked;
import io.github.dgavrikov.core.masking.pattern.MaskedPatternAccountNumber;

public class MaskedAccountNumberSerializer
        extends BaseMasked {
    public MaskedAccountNumberSerializer() {
        this.replaceFunction = MaskedPatternAccountNumber.masking;
    }

}
