package io.github.dgavrikov.core.database.annotation.all;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.GeneratorCreationContext;

import java.lang.reflect.Member;
import java.util.EnumSet;

public class ClearBeforeExecuteImpl implements BeforeExecutionGenerator {

    public ClearBeforeExecuteImpl(
            ClearBeforeExecute annotation,
            Member member,
            GeneratorCreationContext context
    ) {
    }

    @Override
    public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue, EventType eventType) {
        return null;
    }

    @Override
    public EnumSet<EventType> getEventTypes() {
        return EnumSet.of(EventType.INSERT, EventType.UPDATE);
    }
}
