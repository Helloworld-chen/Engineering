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

for(const account of [
 {username:'seller',name:'林同学',peer:'buyer'},
 {username:'buyer',name:'陈同学',peer:'seller'},
])test(`${account.name}可管理自己商品并向其他同学下单`,async({page,browser,baseURL})=>{
 // Use independent products so a student's live purchase cannot affect this regression.
 const ids:string[]=[],titles:string[]=[];
 const keyword="归属过滤验收 "+crypto.randomUUID();
 for(const username of [account.username,account.peer]){
  const context=await browser.newContext({baseURL});
  try{
   const client=context.request;
   let csrf=(await (await client.get('/api/v1/session')).json()).csrfToken;
   const login=await client.post('/api/v1/auth/login',{headers:{'X-CSRF-TOKEN':csrf},data:{username,password:'Campus123!'}});
   expect(login.ok()).toBeTruthy();
   csrf=(await (await client.get('/api/v1/session')).json()).csrfToken;
   const headers={'X-CSRF-TOKEN':csrf,'Idempotency-Key':crypto.randomUUID()};
   const uploaded=await client.post('/api/v1/files',{headers,multipart:{purpose:'PRODUCT',file:{name:'role.png',mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII=','base64')}}});
   expect(uploaded.ok()).toBeTruthy();
   const title=keyword+' '+username;titles.push(title);
   const response=await client.post('/api/v1/products',{headers,data:{title,description:'独立回归商品',category:'其他',price:100,images:[(await uploaded.json()).id]}});
   expect(response.ok()).toBeTruthy();
   const product=await response.json();ids.push(product.id);
   const adminLogin=await client.post('/api/v1/auth/login',{headers,data:{username:'admin',password:'Campus123!'}});
   expect(adminLogin.ok()).toBeTruthy();
   csrf=(await (await client.get('/api/v1/session')).json()).csrfToken;
   const review=await client.post('/api/v1/moderation/products/'+product.id,{headers:{'X-CSRF-TOKEN':csrf,'Idempotency-Key':crypto.randomUUID()},data:{decision:'APPROVED',reason:'回归验收',version:product.version}});
   expect(review.ok()).toBeTruthy();
  }finally{await context.close()}
 }
 await page.goto('/login');
 await page.getByLabel('用户名',{exact:true}).fill(account.username);
 await page.getByLabel('密码',{exact:true}).fill('Campus123!');
 await page.getByRole('button',{name:'登录',exact:true}).click();
 await expect(page.getByRole('link',{name:'我的商品',exact:true})).toBeVisible();
 await expect(page.getByRole('link',{name:'我的订单',exact:true})).toBeVisible();
 await page.getByLabel('商品关键词').fill(keyword);
 await page.getByRole('button',{name:'搜索',exact:true}).click();
 await expect(page.getByRole('heading',{name:titles[1],exact:true})).toBeVisible();
 await expect(page.getByRole('heading',{name:titles[0],exact:true})).toHaveCount(0);
 await expect(page.getByText('共 1 件商品',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'其他',exact:true}).click();
 await expect(page.getByRole('heading',{name:titles[1],exact:true})).toBeVisible();
 await expect(page.getByRole('heading',{name:titles[0],exact:true})).toHaveCount(0);
 await page.getByRole('link',{name:'我的商品',exact:true}).click();
 await expect(page.getByRole('link',{name:titles[0],exact:true})).toBeVisible();
 await page.goto('/products/'+ids[0]);
 await expect(page.getByText('这是你发布的商品，无法向自己下单。你可以购买其他同学发布的商品。',{exact:true})).toBeVisible();
 await expect(page.getByRole('button',{name:'立即下单',exact:true})).toHaveCount(0);
 await expect(page.getByRole('link',{name:'编辑商品',exact:true})).toBeVisible();
 await page.goto('/products/'+ids[1]);
 await page.getByRole('button',{name:'立即下单',exact:true}).click();
 await page.getByRole('button',{name:'确认下单',exact:true}).click();
 await expect(page.getByText('付款剩余时间',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'取消订单',exact:true}).click();
 await page.getByRole('button',{name:'确认取消订单',exact:true}).click();
 await expect(page.locator('main').getByRole('status')).toContainText('订单已取消');
 await page.goto('/');
 await page.getByLabel('商品关键词').fill(keyword);
 await page.getByRole('button',{name:'搜索',exact:true}).click();
 await expect(page.getByText('共 1 件商品',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'退出',exact:true}).click();
 await expect(page.getByRole('heading',{name:titles[0],exact:true})).toBeVisible();
 await expect(page.getByRole('heading',{name:titles[1],exact:true})).toBeVisible();
 await expect(page.getByText('共 2 件商品',{exact:true})).toBeVisible();
 await page.goto('/login');
 await page.getByLabel('用户名',{exact:true}).fill(account.peer);
 await page.getByLabel('密码',{exact:true}).fill('Campus123!');
 await page.getByRole('button',{name:'登录',exact:true}).click();
 await page.getByLabel('商品关键词').fill(keyword);
 await page.getByRole('button',{name:'搜索',exact:true}).click();
 await expect(page.getByRole('heading',{name:titles[0],exact:true})).toBeVisible();
 await expect(page.getByRole('heading',{name:titles[1],exact:true})).toHaveCount(0);
 await expect(page.getByText('共 1 件商品',{exact:true})).toBeVisible();
});
