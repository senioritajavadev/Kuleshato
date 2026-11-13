package kuleshato;

import org.junit.jupiter.api.Test;

final class TestSuiteTest {
    @Test
    void runsJavaProjectRegressionSuite() throws Exception {
        TestSuite.runAll();
    }
}
