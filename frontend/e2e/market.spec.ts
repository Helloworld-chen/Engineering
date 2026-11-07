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

test('错误反馈、登录跳转与管理员页面权限',async({page})=>{
 await page.goto('/orders');
 await expect(page).toHaveURL(/\/login\?redirect=\/orders$/);
 await page.getByLabel('用户名',{exact:true}).fill('buyer');
 await page.getByLabel('密码',{exact:true}).fill('WrongPassword!');
 await page.getByRole('button',{name:'登录',exact:true}).click();
 await expect(page.getByRole('alert')).toContainText('用户名或密码不正确');
 await page.getByLabel('密码',{exact:true}).fill('Campus123!');
 await page.getByRole('button',{name:'登录',exact:true}).click();
 await expect(page.getByRole('heading',{name:'我的订单',exact:true})).toBeVisible();
 await page.goto('/moderation');
 await expect(page).toHaveURL(/\/$/);
 await expect(page.getByRole('link',{name:'商品审核'})).toHaveCount(0);
 const response=await page.request.get('/api/v1/moderation/products');
 expect(response.status()).toBe(403);
});

test('会话过期后回到登录，退出响应丢失后核实真实会话',async({page})=>{
 async function enter(){
  await page.getByLabel('用户名',{exact:true}).fill('buyer');
  await page.getByLabel('密码',{exact:true}).fill('Campus123!');
  await page.getByRole('button',{name:'登录',exact:true}).click();
 }
 await page.goto('/login');
 await enter();
 await page.getByRole('link',{name:'我的订单',exact:true}).click();
 await expect(page.getByRole('heading',{name:'我的订单',exact:true})).toBeVisible();
 await page.context().setOffline(true);
 await page.context().clearCookies();
 await page.context().setOffline(false);
 await expect(page).toHaveURL(/\/login\?redirect=\/orders$/);
 await expect(page.getByRole('link',{name:'我的订单',exact:true})).toHaveCount(0);
 await enter();
 await expect(page.getByRole('heading',{name:'我的订单',exact:true})).toBeVisible();

 await page.route('**/api/v1/auth/logout',async route=>{
  const response=await route.fetch();
  expect(response.ok()).toBeTruthy();
  await route.abort('failed');
 });
 await page.getByRole('button',{name:'退出',exact:true}).click();
 await expect(page).toHaveURL(/\/$/);
 await expect(page.getByRole('link',{name:'登录',exact:true})).toBeVisible();
 await expect(page.getByRole('link',{name:'我的订单',exact:true})).toHaveCount(0);
});

test('注册表单为无效用户名提供关联提示',async({page})=>{
 await page.goto('/register');
 const username=page.getByLabel('用户名',{exact:true});
 await username.fill('不符合格式');
 await page.getByLabel('昵称',{exact:true}).fill('同学');
 await page.getByLabel('密码',{exact:true}).fill('Campus123!');
 await page.getByRole('button',{name:'注册',exact:true}).click();
 await expect(username).toHaveAttribute('aria-invalid','true');
 await expect(username).toHaveAccessibleDescription(/用户名需 3 至 32 位/);
 await expect(username).toBeFocused();
});
