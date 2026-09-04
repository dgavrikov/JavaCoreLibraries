package io.github.dgavrikov.core.masking.annotation.impl;

import io.github.dgavrikov.core.masking.BaseMasked;
import io.github.dgavrikov.core.masking.pattern.MaskedPatternPhone;

public class MaskedPhoneSerializer
        extends BaseMasked {
    public MaskedPhoneSerializer() {
        this.replaceFunction = MaskedPatternPhone.masking;
    }
}
