package io.github.stateofme;

/** Local selection policy; empty allowlist and unknown modes fail closed. */
public final class AppFilter {
 public static boolean allows(String mode,String selected,String blocked,String candidates,String item){
  if(item==null||item.isEmpty())return false;
  if("blacklist".equals(mode))return contains(candidates,item)&&!contains(blocked,item);
  return "whitelist".equals(mode)&&contains(selected,item);
 }
 static boolean contains(String csv,String item){for(String part:csv.split(","))if(part.trim().equals(item))return true;return false;}
}
