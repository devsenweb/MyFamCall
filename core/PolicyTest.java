import com.myfamcall.app.CallPolicy;
import com.myfamcall.app.IceGeneration;
public class PolicyTest {
    static int assertions;
    static void check(boolean value) { assertions++; if (!value) throw new AssertionError("Assertion " + assertions); }
    public static void main(String[] args) {
        check(CallPolicy.incomingFresh(10000, 10000, 99999));
        check(!CallPolicy.incomingFresh(55000, 10000, 99999));
        check(!CallPolicy.incomingFresh(10000, 20000, 99999));
        check(!CallPolicy.incomingFresh(10000, 10000, 10000));
        check(CallPolicy.shouldRestart(true, false, 1, 6000, 0));
        check(!CallPolicy.shouldRestart(true, true, 1, 6000, 0));
        check(!CallPolicy.shouldRestart(false, false, 1, 6000, 0));
        check(!CallPolicy.shouldRestart(true, false, 32, 6000, 0));
        check(!CallPolicy.shouldRestart(true, false, 1, 1000, 0));
        check(CallPolicy.validRemoteRevision(2, 1, true));
        check(!CallPolicy.validRemoteRevision(1, 1, true));
        check(!CallPolicy.validRemoteRevision(1, 2, false));
        check(CallPolicy.validRemoteRevision(2, 2, false));
        check(!CallPolicy.validRemoteRevision(33, 33, false));
        check(CallPolicy.weakNetwork(0.9, 500000));
        check(CallPolicy.weakNetwork(0.1, 100000));
        check(!CallPolicy.weakNetwork(0.1, 500000));
        check(!CallPolicy.weakNetwork(0.1, 0));
        java.util.Set<String> ufrags=IceGeneration.ufrags("v=0\r\na=ice-ufrag:current\r\n");
        check(ufrags.contains("current"));
        check(IceGeneration.matches("candidate:x ufrag current",ufrags,false));
        check(!IceGeneration.matches("candidate:x ufrag old",ufrags,false));
        check(!IceGeneration.matches("candidate:x typ host",ufrags,false));
        check(IceGeneration.matches("candidate:x typ host",ufrags,true));
        System.out.println("PASS: " + assertions + " policy assertions");
    }
}
