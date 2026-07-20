public final class TestRunner {
    public static void main(String[] args) throws Exception {
        Class.forName("com.mina.yuedu.core.CoreTest").getMethod("runAll").invoke(null);
        Class.forName("com.mina.yuedu.network.QueueStateTest").getMethod("runAll").invoke(null);
        Class.forName("com.mina.yuedu.network.SourceParserTest").getMethod("runAll").invoke(null);
        Class.forName("com.mina.yuedu.network.YckPolicyTest").getMethod("runAll").invoke(null);
        System.out.println("ALL TESTS PASSED");
    }
}
