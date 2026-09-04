package io.github.dgavrikov.core.database.annotation.string;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.GeneratorCreationContext;

import java.lang.reflect.Member;
import java.util.EnumSet;

public class TruncateStringImpl implements BeforeExecutionGenerator {
    private final int maxLength;

    public TruncateStringImpl(TruncateString annotation, Member member, GeneratorCreationContext context) {
        this.maxLength = annotation.maxLength();
    }

    @Override
    public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue, EventType eventType) {
        if (currentValue instanceof String originalString) {
            return StringUtils.truncate(originalString, this.maxLength);
        }
        return currentValue;
    }

    @Override
    public EnumSet<EventType> getEventTypes() {
        return EnumSet.of(EventType.INSERT, EventType.UPDATE);
    }
}
