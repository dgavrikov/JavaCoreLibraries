package com.github.dgavrikov.core.masking.annotation.impl;

import com.github.dgavrikov.core.masking.BaseMasked;
import com.github.dgavrikov.core.masking.pattern.MaskedPatternPhone;

public class MaskedPhoneSerializer
        extends BaseMasked {
    public MaskedPhoneSerializer() {
        this.replaceFunction = MaskedPatternPhone.masking;
    }
}
