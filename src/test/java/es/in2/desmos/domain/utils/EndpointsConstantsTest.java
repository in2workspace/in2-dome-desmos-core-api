package es.in2.desmos.domain.utils;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EndpointsConstantsTest {
    @Test
    void testPrivateConstructorThrowsException() throws NoSuchMethodException {
        Constructor<EndpointsConstants> constructor = EndpointsConstants.class.getDeclaredConstructor();

        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class);
    }
}