import {test,expect,type Page,type APIRequestContext} from '@playwright/test';

async function login(page:Page,username:string){
 await page.goto('/login');
 await page.getByLabel('用户名',{exact:true}).fill(username);
 await page.getByLabel('密码',{exact:true}).fill('Campus123!');
 await page.getByRole('button',{name:'登录',exact:true}).click();
 await expect(page).toHaveURL(/\/$/);
}
async function post(client:APIRequestContext,path:string,data:unknown){
 const {csrfToken}=await (await client.get('/api/v1/session')).json();
 const response=await client.post('/api/v1'+path,{headers:{'X-CSRF-TOKEN':csrfToken,'Idempotency-Key':crypto.randomUUID()},data});
 expect(response.ok(),await response.text()).toBeTruthy();
 return response.json();
}
async function publish(seller:APIRequestContext,admin:APIRequestContext,title:string){
 const {csrfToken}=await (await seller.get('/api/v1/session')).json();
 const upload=await seller.post('/api/v1/files',{headers:{'X-CSRF-TOKEN':csrfToken},multipart:{purpose:'PRODUCT',file:{name:'notebook.png',mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII=','base64')}}});
 expect(upload.ok()).toBeTruthy();
 const product=await post(seller,'/products',{title,description:'卖家交易进度回归商品',category:'图书',price:100,images:[(await upload.json()).id]});
 await post(admin,'/moderation/products/'+product.id,{decision:'APPROVED',reason:'测试资料完整',version:product.version});
 return product;
}

test('卖家商品实时展示下单付款、优先排序、售后入口及重连恢复',async({browser,baseURL},info)=>{
 test.setTimeout(60000);
 const contexts=await Promise.all(['seller','buyer','admin'].map(async username=>{
  const context=await browser.newContext({baseURL});
  const page=await context.newPage();await login(page,username);
  return {context,page,client:context.request};
 }));
 const [seller,buyer,admin]=contexts;
 const title='卖家进度 · 校园笔记本 '+crypto.randomUUID();
 try{
  const product=await publish(seller.client,admin.client,title);
  await publish(seller.client,admin.client,'新发布但无订单 '+crypto.randomUUID());
  await seller.page.goto('/my/products');
  const row=seller.page.getByRole('article').filter({hasText:title});
  await expect(row.getByText('在售',{exact:true})).toBeVisible();
  const order=await post(buyer.client,'/orders',{productId:product.id});
  await expect(row.getByText('买家已下单，等待付款',{exact:true})).toBeVisible();
  await expect(row.getByRole('link',{name:'查看订单',exact:true})).toHaveAttribute('href','/orders/'+order.id);
  await expect(seller.page.getByRole('article').first()).toContainText(title);
  await expect(row.getByRole('link',{name:'编辑',exact:true})).toHaveCount(0);

  await seller.context.setOffline(true);
  await post(buyer.client,'/orders/'+order.id+'/payment',{});
  await seller.context.setOffline(false);
  await expect(row.getByText('买家已付款，待你发货',{exact:true})).toBeVisible({timeout:15000});
  await expect(row.getByText('已售出',{exact:true})).toBeVisible();
  await expect(row.getByRole('link',{name:'去发货',exact:true})).toHaveAttribute('href','/orders/'+order.id);
  await seller.page.reload();
  await expect(row.getByText('买家已付款，待你发货',{exact:true})).toBeVisible();
  for(const width of [1440,390]){
   await seller.page.setViewportSize({width,height:900});
   expect(await seller.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
   await seller.page.screenshot({path:info.outputPath('seller-progress-'+width+'.png'),fullPage:true});
  }
  await row.getByRole('link',{name:'去发货',exact:true}).click();
  await expect(seller.page).toHaveURL(new RegExp('/orders/'+order.id+'$'));
  await expect(seller.page.getByRole('button',{name:'发货',exact:true})).toBeVisible();
  await seller.page.goto('/my/products');
  const refund=await post(buyer.client,'/cases',{orderId:order.id,kind:'REFUND',reason:'买家申请退款'});
  await expect(row.getByText('待你审核退款',{exact:true})).toBeVisible();
  await expect(row.getByRole('link',{name:'去发货',exact:true})).toHaveCount(0);
  await row.getByRole('link',{name:'处理售后',exact:true}).click();
  await expect(seller.page).toHaveURL(new RegExp('/cases/'+refund.id+'$'));
  await expect(seller.page.getByRole('button',{name:'同意退款',exact:true})).toBeVisible();
  await post(seller.client,'/cases/'+refund.id+'/actions',{action:'REFUND',reason:'同意退款'});
  await seller.page.goto('/my/products');
  await expect(row.getByText('订单已退款',{exact:true})).toBeVisible();
  await expect(row.getByText('已售出',{exact:true})).toBeVisible();

  await seller.page.route('**/api/v1/orders?side=selling',route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'UNAVAILABLE',message:'订单暂时无法读取，请重试'})}));
  await seller.page.reload();
  await expect(seller.page.getByRole('alert')).toContainText('订单暂时无法读取');
  await expect(seller.page.getByText('还没有发布商品')).toHaveCount(0);
  await seller.page.unroute('**/api/v1/orders?side=selling');
  await seller.page.getByRole('button',{name:'重新读取',exact:true}).click();
  await expect(row.getByText('订单已退款',{exact:true})).toBeVisible();
  // Switch the same browser to another account: the seller's products must disappear.
  await post(seller.client,'/auth/logout',{});
  await login(seller.page,'buyer');
  await seller.page.goto('/my/products');
  await expect(seller.page.getByRole('heading',{name:'我的商品',exact:true})).toBeVisible();
  await expect(seller.page.getByRole('article').filter({hasText:title})).toHaveCount(0);
 }finally{await Promise.all(contexts.map(({context})=>context.close()))}
});

test('取消及付款超时恢复在售，再次购买关联新订单',async({browser,baseURL})=>{
 // Advancing the global demo clock is allowed only on the explicitly isolated test server.
 test.skip(process.env.SELLER_E2E_ISOLATED!=='1','需要独立数据库，避免改变演示网站的业务时钟');
 const contexts=await Promise.all(['seller','buyer','admin'].map(async username=>{
  const context=await browser.newContext({baseURL});
  const page=await context.newPage();await login(page,username);
  return {context,page,client:context.request};
 }));
 const [seller,buyer,admin]=contexts;
 const title='订单恢复 · 笔记本 '+crypto.randomUUID();
 try{
  const product=await publish(seller.client,admin.client,title);
  await seller.page.goto('/my/products');
  const row=seller.page.getByRole('article').filter({hasText:title});
  const first=await post(buyer.client,'/orders',{productId:product.id});
  await expect(row.getByText('买家已下单，等待付款',{exact:true})).toBeVisible();
  await post(buyer.client,'/orders/'+first.id+'/cancel',{});
  await expect(row.getByText('最近订单已由买家取消',{exact:true})).toBeVisible();
  await expect(row.getByText('在售',{exact:true})).toBeVisible();
  const second=await post(buyer.client,'/orders',{productId:product.id});
  await expect(row.getByRole('link',{name:'查看订单',exact:true})).toHaveAttribute('href','/orders/'+second.id);
  await post(admin.client,'/demo/advance',{millis:120001});
  await expect(row.getByText('最近订单付款超时，已关闭',{exact:true})).toBeVisible();
  await expect(row.getByText('在售',{exact:true})).toBeVisible();
  const third=await post(buyer.client,'/orders',{productId:product.id});
  await post(buyer.client,'/orders/'+third.id+'/payment',{});
  await expect(row.getByRole('link',{name:'去发货',exact:true})).toHaveAttribute('href','/orders/'+third.id);
  await expect(row.getByText('最近订单付款超时，已关闭',{exact:true})).toHaveCount(0);
 }finally{await Promise.all(contexts.map(({context})=>context.close()))}
});
