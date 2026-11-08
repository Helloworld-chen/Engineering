package cn.edu.campus.identity;

import cn.edu.campus.shared.Actor;

/** M03. Implementations authenticate passwords and return server-owned roles. */
public interface IdentityPort {
  Actor authenticate(String username, String password);

  Actor register(String username, String password, String displayName);

  Actor find(String id);

  Actor updateProfile(Actor actor, String displayName);
}
