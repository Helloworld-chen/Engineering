package cn.edu.campus;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DemoResetTest extends WorkflowTestSupport {
    private static final String RESET = "/demo/reset";

    @Test void onlyAdminCanResetWithExplicitConfirmation() throws Exception {
        var buyer = new Browser("buyer");
        var admin = new Browser("admin");
        String order = paid(buyer);

        buyer.call("POST", RESET, Map.of("confirmation", "RESET"), UUID.randomUUID().toString(), 403);
        admin.call("POST", RESET, Map.of("confirmation", "no"), UUID.randomUUID().toString(), 400);
        assertThat(state(order)).isEqualTo("AWAITING_SHIPMENT");
        assertThat(db.queryForObject("SELECT balance FROM accounts WHERE user_id='buyer'", Long.class)).isEqualTo(10_000);
    }

    @Test void resetClearsTradeStateAndRestoresDemoDefaultsWithoutRemovingUsers() throws Exception {
        var buyer = new Browser("buyer");
        var admin = new Browser("admin");
        var seller = new Browser("seller");
        String order = paid(buyer);
        seller.post("/orders/" + order + "/shipment", evidence(false));
        advance(10_000);
        long userCount = db.queryForObject("SELECT COUNT(*) FROM users", Long.class);

        admin.post(RESET, Map.of("confirmation", "RESET"));

        buyer.call("GET", "/orders/" + order, null, null, 404);
        for (String table : new String[]{"orders", "shipments", "product_holds", "escrows", "money_operations", "deadlines"}) {
            assertThat(db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isEqualTo(userCount);
        assertThat(db.queryForObject("SELECT balance FROM accounts WHERE user_id='buyer'", Long.class)).isEqualTo(20_000);
        assertThat(db.queryForObject("SELECT balance FROM accounts WHERE user_id='seller'", Long.class)).isEqualTo(5_000);
        assertThat(db.queryForObject("SELECT offset_millis FROM clock_state WHERE id=1", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT frozen_at FROM clock_state WHERE id=1", Long.class)).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM products WHERE id LIKE 'demo-product-%' AND sale_status='AVAILABLE'", Integer.class)).isEqualTo(4);
    }
    @Test void repeatedResetKeyDoesNotDeleteLaterOrders() throws Exception {
        var admin = new Browser("admin");
        var buyer = new Browser("buyer");
        var confirmation = Map.of("confirmation", "RESET");
        String key = UUID.randomUUID().toString();
        admin.call("POST", RESET, confirmation, key, 200);
        String order = order(buyer);
        admin.call("POST", RESET, confirmation, key, 200);
        assertThat(state(order)).isEqualTo("PENDING_PAYMENT");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM product_holds WHERE order_id=?", Integer.class, order)).isOne();
    }
}
