package com.github.dgavrikov.core.masking.annotation.impl;

import com.github.dgavrikov.core.masking.BaseMasked;
import com.github.dgavrikov.core.masking.pattern.MaskedPatternAccountNumber;

public class MaskedAccountNumberSerializer
        extends BaseMasked {
    public MaskedAccountNumberSerializer() {
        this.replaceFunction = MaskedPatternAccountNumber.masking;
    }

}
