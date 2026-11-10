package cn.edu.campus.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.edu.campus.shared.Actor;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

class SessionsTest {
  @Test
  void realSessionHasFixedExpiryAndAccessDoesNotExtendIt() {
    var users = mock(IdentityPort.class);
    var actor = new Actor("id-1", "buyer", "买家", "USER");
    when(users.find(actor.id())).thenReturn(actor);
    var sessions = new Sessions(users, "real");
    var request = new MockHttpServletRequest();
    var session = new MockHttpSession();
    request.setSession(session);

    var before = Instant.now();
    sessions.signIn(request, actor);
    var after = Instant.now();
    var deadline = (Instant) session.getAttribute(Sessions.EXPIRES_AT);
    assertThat(deadline).isBetween(before.plus(Duration.ofHours(2)), after.plus(Duration.ofHours(2)));
    assertThat(sessions.optional(request)).isEqualTo(actor);
    assertThat(session.getAttribute(Sessions.EXPIRES_AT)).isEqualTo(deadline);

    session.setAttribute(Sessions.EXPIRES_AT, Instant.EPOCH);
    assertThat(sessions.optional(request)).isNull();
    assertThat(session.isInvalid()).isTrue();
  }
}
