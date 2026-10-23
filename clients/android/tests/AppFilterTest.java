import io.github.stateofme.AppFilter;
public class AppFilterTest {
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args){
  check(!AppFilter.allows("whitelist","","","app","app"));
  check(AppFilter.allows("whitelist","app,other","","app","app"));
  check(!AppFilter.allows("whitelist","app.other","","app","app"));
  check(!AppFilter.allows("blacklist","","app","app","app"));
  check(AppFilter.allows("blacklist","","secret","app","app"));
  check(!AppFilter.allows("off","app","","app","app"));
  check(!AppFilter.allows("unknown","app","","app","app"));
  check(!AppFilter.allows("blacklist","","","app",""));
  check(!AppFilter.allows("blacklist","","","app","invisible.app"));
  System.out.println("App filter contracts passed");
 }
}
