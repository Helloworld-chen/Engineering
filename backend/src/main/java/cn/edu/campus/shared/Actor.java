package cn.edu.campus.shared;

public record Actor(String id, String username, String displayName, String role) {
  public boolean is(String role) {
    return role.equals(this.role);
  }
}
