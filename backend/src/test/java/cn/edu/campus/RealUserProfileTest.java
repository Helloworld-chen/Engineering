package cn.edu.campus;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
    "market.providers.identity=real", "market.providers.product=real",
    "market.providers.aftersales=real"
})
class RealUserProfileTest extends UserProfileTest {}
