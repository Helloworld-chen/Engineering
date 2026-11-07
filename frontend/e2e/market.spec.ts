import { test,expect } from '@playwright/test';
test('访客浏览、登录和订单页面权限',async({page})=>{
 await page.goto('/');await expect(page.getByRole('heading',{name:'发现闲置，让物品继续有用'})).toBeVisible();
 await page.getByRole('link',{name:'登录',exact:true}).click();
 await page.getByLabel('用户名', {exact:true}).fill('buyer');await page.getByLabel('密码',{exact:true}).fill('Campus123!');
 await page.getByRole('button',{name:'登录',exact:true}).click();
 await expect(page.getByRole('link',{name:'我的订单',exact:true})).toBeVisible();
 await page.getByRole('link',{name:'我的订单',exact:true}).click();await expect(page.getByRole('heading',{name:'我的订单',exact:true})).toBeVisible();
});
for(const width of [1440,1280,390])test(`商品页面无横向溢出 ${width}`,async({page})=>{
 await page.setViewportSize({width,height:900});await page.goto('/');
 await expect(page.getByRole('heading',{name:'发现闲置，让物品继续有用'})).toBeVisible();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
 await page.screenshot({path:`test-results/catalog-${width}.png`,fullPage:true});
});
