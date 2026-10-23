import io.github.stateofme.UpdateTiming;
public class UpdateTimingTest {
 public static void main(String[] args){
  if(UpdateTiming.delay(1,0)!=0)throw new AssertionError("initial");
  if(UpdateTiming.delay(1000,1000)!=1100)throw new AssertionError("burst");
  if(UpdateTiming.delay(1500,1000)!=600)throw new AssertionError("remaining interval");
  if(UpdateTiming.delay(5000,1000)!=0)throw new AssertionError("heartbeat");
  System.out.println("Update timing passed");
 }
}
