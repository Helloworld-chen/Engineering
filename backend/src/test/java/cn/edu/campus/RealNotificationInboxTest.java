package cn.edu.campus;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "market.scheduling=false", "market.providers.notification=real",
    "market.upload-dir=${java.io.tmpdir}/campus-market-test-uploads"
})
class RealNotificationInboxTest extends NotificationInboxTest {}
