import {test,expect,type Page,type Locator} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {writeFile} from 'node:fs/promises';

test.beforeEach(()=>test.skip(process.env.A11Y_ISOLATED!=='1','仅在独立数据库执行无障碍验收'));

async function login(page:Page,user='buyer'){
 await page.goto('/login');
 await page.getByLabel('用户名',{exact:true}).fill(user);
 await page.getByLabel('密码',{exact:true}).fill('Campus123!');
 await page.getByRole('button',{name:'登录',exact:true}).click();
 await expect(page).toHaveURL(new RegExp('/'+(user==='admin'?'admin':user==='support'?'support':'')+'$'));
}
async function scan(page:Page,name:string,info:any){
 await expect(page.locator('main h1')).toBeVisible();
 await expect(page.locator('[aria-busy=true]')).toHaveCount(0);
 await page.evaluate(()=>Promise.all(document.getAnimations().filter(a=>a.effect?.getTiming().iterations!==Infinity).map(a=>a.finished.catch(()=>{}))));
 const result=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa','wcag22aa','best-practice']).analyze();
 const reportPath=info.outputPath(name+'.json');
 await writeFile(reportPath,JSON.stringify({url:page.url(),timestamp:result.timestamp,testEngine:result.testEngine,violations:result.violations,incomplete:result.incomplete,passes:result.passes.map(x=>x.id)},null,2));
 await info.attach(name+'.json',{path:reportPath,contentType:'application/json'});
 expect(result.violations.map(v=>({id:v.id,nodes:v.nodes.map(n=>({target:n.target,summary:n.failureSummary}))}))).toEqual([]);
 // axe flags names on generic div/span elements as requiring manual review.
 // These controls must expose an explicit supported role, rather than leaving that finding unresolved.
 expect(result.incomplete.filter(v=>v.id==='aria-prohibited-attr')).toEqual([]);
}
async function tabTo(page:Page,target:Locator){
 await expect(target).toBeVisible();
 for(let n=0;n<100;n++){
  if(await target.evaluate(e=>e===document.activeElement))return;
  await page.keyboard.press('Tab');
 }
 throw new Error('键盘无法到达 '+await target.first().evaluate(e=>e.outerHTML));
}

async function expectBackgroundHidden(page:Page){
 const session=await page.context().newCDPSession(page);
 try{await expect.poll(async()=>{
  const {nodes}=await session.send('Accessibility.getFullAXTree');
  return nodes.filter(n=>!n.ignored&&n.role?.value==='main').length;
 }).toBe(0)}finally{await session.detach()}
 // Inert must also prevent an explicit background focus attempt.
 await page.locator('main').evaluate(e=>(e as HTMLElement).focus());
 expect(await page.getByRole('dialog').evaluate(e=>e.contains(document.activeElement))).toBe(true);
}

test('页面切换更新标题、定位异步标题，筛选保留焦点',async({page})=>{
 await page.goto('/');
 await page.getByRole('link',{name:'登录',exact:true}).click();
 await expect(page).toHaveTitle(/登录校园集市.*校园集市/);
 await expect(page.getByRole('heading',{level:1})).toBeFocused();
 await page.getByRole('link',{name:'浏览商品'}).click();
 const filter=page.getByRole('button',{name:'图书',exact:true});
 await filter.click();await expect(filter).toBeFocused();
 await expect(page.locator('[aria-busy=true]')).toHaveCount(0);
 await expect(filter).toBeFocused();
 await page.getByRole('button',{name:'全部商品',exact:true}).click();
 await page.locator('.product-link').first().click();
 await expect(page.getByRole('heading',{level:1})).toBeFocused();
 await expect(page).toHaveTitle(new RegExp(await page.getByRole('heading',{level:1}).innerText()));
});

test('跳转链接可用且手机导航隔离背景、循环焦点并用 Escape 返回',async({page})=>{
 await page.setViewportSize({width:390,height:844});await page.goto('/');
 await page.keyboard.press('Tab');
 await expect(page.getByRole('link',{name:'跳转到主要内容'})).toBeFocused();
 await page.keyboard.press('Enter');await expect(page.locator('main')).toBeFocused();
 const toggle=page.getByRole('button',{name:'展开导航'});
 await tabTo(page,toggle);await page.keyboard.press('Enter');
 const nav=page.getByRole('dialog',{name:'主导航'});
 await expect(nav).toBeVisible();
 await expectBackgroundHidden(page);
 for(let n=0;n<12;n++){await page.keyboard.press('Tab');expect(await nav.evaluate(e=>e.contains(document.activeElement))).toBe(true)}
 await page.keyboard.press('Escape');await expect(nav).toBeHidden();await expect(toggle).toBeFocused();
 await expect(page.getByRole('main')).toBeVisible();
});

test('详情加载失败更新页面标题并聚焦错误，重试恢复商品标题',async({page})=>{
 await page.goto('/');
 await page.route('**/api/v1/products/demo-product-0',route=>route.fulfill({status:404,contentType:'application/json',body:JSON.stringify({code:'NOT_FOUND',message:'商品不存在'})}));
 await page.locator('a[href="/products/demo-product-0"]').first().click();
 const error=page.getByRole('heading',{name:'暂时无法读取'});
 await expect(error).toBeFocused();
 await expect(page).toHaveTitle(/商品详情：暂时无法读取.*校园集市/);
 await page.unroute('**/api/v1/products/demo-product-0');
 await page.getByRole('button',{name:'重新读取'}).click();
 await expect(page.getByRole('heading',{level:1})).toBeVisible();
 await expect(page).toHaveTitle(new RegExp(await page.getByRole('heading',{level:1}).innerText()));
});

test('工作人员手机抽屉隔离背景并恢复菜单焦点',async({page})=>{
 await login(page,'admin');await page.setViewportSize({width:390,height:844});
 const toggle=page.getByRole('button',{name:'展开导航'});await toggle.click();
 await expect(page.getByRole('dialog',{name:'管理员工作台'})).toBeVisible();
 await expectBackgroundHidden(page);
 await page.keyboard.press('Escape');await expect(toggle).toBeFocused();
});

test('表单校验错误关联字段，修正输入后清除旧错误',async({page})=>{
 await page.goto('/register');
 await page.getByLabel('用户名',{exact:true}).fill('a11yvalid');await page.getByLabel('昵称',{exact:true}).fill('测试同学');
 await page.getByLabel('密码',{exact:true}).fill('Campus123!');
 const confirmation=page.getByLabel('确认密码',{exact:true});await confirmation.fill('different');
 await page.getByRole('button',{name:'注册',exact:true}).click();
 await expect(confirmation).toBeFocused();await expect(confirmation).toHaveAttribute('aria-invalid','true');
 await expect(confirmation).toHaveAccessibleDescription('两次输入的密码不一致');
 await confirmation.fill('Campus123!');await expect(confirmation).not.toHaveAttribute('aria-invalid','true');
});

test('业务确认弹窗支持键盘循环、背景隔离与取消后返回焦点',async({page},info)=>{
 await login(page);await page.goto('/products/demo-product-0');
 const trigger=page.getByRole('button',{name:'立即下单'});await trigger.focus();await page.keyboard.press('Enter');
 const dialog=page.getByRole('dialog',{name:'下单',exact:true});await expect(dialog).toBeVisible();
 await expectBackgroundHidden(page);
 for(let n=0;n<10;n++){await page.keyboard.press('Tab');expect(await dialog.evaluate(e=>e.contains(document.activeElement))).toBe(true)}
 await scan(page,'下单弹窗',info);
 await page.keyboard.press('Escape');await expect(dialog).toBeHidden();await expect(trigger).toBeFocused();
});

test('上传图片移除按钮可区分，移除后焦点仍在上传区域',async({page})=>{
 await login(page,'seller');await page.goto('/publish');
 const input=page.getByLabel(/^商品照片/);
 const buffer=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII=','base64');
 await input.setInputFiles([{name:'one.png',mimeType:'image/png',buffer},{name:'two.png',mimeType:'image/png',buffer}]);
 await expect(page.getByAltText(/商品照片预览/)).toHaveCount(2);
 const remove=page.getByRole('button',{name:'移除商品照片第 1 张',exact:true});
 await tabTo(page,remove);await page.keyboard.press('Enter');
 await expect(page.getByAltText(/商品照片预览/)).toHaveCount(1);await expect(input).toBeFocused();
});

test('强制颜色模式保留输入框实体边界与当前选项标识',async({page},info)=>{
 await page.emulateMedia({forcedColors:'active'});await page.goto('/login');
 const input=page.getByLabel('用户名',{exact:true});
 const border=await input.locator('..').evaluate(e=>({width:getComputedStyle(e).borderWidth,style:getComputedStyle(e).borderStyle}));
 expect(border.width).not.toBe('0px');expect(border.style).not.toBe('none');
 await page.goto('/');const active=page.getByRole('button',{name:'全部商品',exact:true});
 await expect(active).toHaveAttribute('aria-pressed','true');
 expect(await active.evaluate(e=>getComputedStyle(e).outlineStyle)).not.toBe('none');
 await page.screenshot({path:info.outputPath('forced-colors.png'),fullPage:true});
});

for(const group of ['游客','普通用户','管理员','客服'])test('axe 无障碍规则检查：'+group,async({page},info)=>{
 test.setTimeout(120000);
 const paths=group==='游客'?['/','/login','/register','/products/demo-product-0','/missing-a11y-page']:group==='普通用户'?['/','/publish','/my/products','/orders','/cases','/profile','/notifications','/users/buyer']:group==='管理员'?['/admin','/moderation','/reports','/profile']:['/support','/cases','/profile'];
 if(group!=='游客')await login(page,group==='普通用户'?'buyer':group==='管理员'?'admin':'support');
 for(const path of paths){await page.goto(path);await scan(page,group+path.replaceAll('/','-'),info)}
});

test('320 CSS 像素重排及200%文本大小保留内容和可操作控件',async({page},info)=>{
 await login(page);await page.setViewportSize({width:320,height:800});
 for(const path of ['/','/profile','/publish','/orders','/notifications']){
  await page.goto(path);await expect(page.locator('[aria-busy=true]')).toHaveCount(0);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 }
 await page.setViewportSize({width:1280,height:900});await page.goto('/publish');
 await page.evaluate(()=>{
  for(const el of document.querySelectorAll<HTMLElement>('body *')){const s=getComputedStyle(el);el.dataset.a11yFont=String(parseFloat(s.fontSize));el.dataset.a11yLine=String(parseFloat(s.lineHeight))}
  for(const el of document.querySelectorAll<HTMLElement>('[data-a11y-font]')){el.style.fontSize=Number(el.dataset.a11yFont)*2+'px';if(Number(el.dataset.a11yLine))el.style.lineHeight=Number(el.dataset.a11yLine)*2+'px'}
 });
 await expect(page.getByRole('button',{name:'提交审核'})).toBeVisible();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:info.outputPath('text-200-percent.png'),fullPage:true});
});

for(const forced of [false,true])test('键盘完成下单付款、发货收货和评价：'+(forced?'强制颜色':'普通显示'),async({browser,baseURL},info)=>{
 test.setTimeout(120000);
 const contexts=await Promise.all(['seller','buyer','admin'].map(async user=>{
  const context=await browser.newContext({baseURL,forcedColors:forced?'active':'none',reducedMotion:'reduce'});
  const page=await context.newPage();await login(page,user);return{context,page};
 }));
 const [seller,buyer,admin]=contexts;
 async function post(page:Page,path:string,data:unknown){
  const {csrfToken}=await(await page.request.get('/api/v1/session')).json();
  const response=await page.request.post('/api/v1'+path,{headers:{'X-CSRF-TOKEN':csrfToken,'Idempotency-Key':crypto.randomUUID()},data});
  expect(response.ok(),await response.text()).toBe(true);return response.json();
 }
 async function press(page:Page,target:Locator){await tabTo(page,target);await page.keyboard.press('Enter')}
 async function type(page:Page,target:Locator,value:string){await tabTo(page,target);await page.keyboard.insertText(value)}
 const picture={name:'a11y.png',mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII=','base64')};
 try{
  // Setup creates an approved fixture through the real API. All trade actions below use the UI.
  const {csrfToken}=await(await seller.page.request.get('/api/v1/session')).json();
  const upload=await seller.page.request.post('/api/v1/files',{headers:{'X-CSRF-TOKEN':csrfToken},multipart:{purpose:'PRODUCT',file:picture}});
  expect(upload.ok()).toBe(true);
  const product=await post(seller.page,'/products',{title:'无障碍键盘验收 '+crypto.randomUUID(),description:'检查完整交易交互。',category:'图书',price:100,images:[(await upload.json()).id]});
  await post(admin.page,'/moderation/products/'+product.id,{decision:'APPROVED',reason:'独立无障碍测试',version:product.version});
  await buyer.page.goto('/products/'+product.id);
  await press(buyer.page,buyer.page.getByRole('button',{name:'立即下单',exact:true}));
  await press(buyer.page,buyer.page.getByRole('button',{name:'确认下单',exact:true}));
  await expect(buyer.page).toHaveURL(/\/orders\//);const orderPath=new URL(buyer.page.url()).pathname;
  await scan(buyer.page,'待付款订单',info);
  await press(buyer.page,buyer.page.getByRole('button',{name:'付款',exact:true}));
  await scan(buyer.page,'付款确认',info);
  await press(buyer.page,buyer.page.getByRole('button',{name:'确认付款',exact:true}));
  await expect(buyer.page.getByText('订单已付款，等待卖家发货。')).toBeVisible();
  await seller.page.goto(orderPath);
  await press(seller.page,seller.page.getByRole('button',{name:'发货',exact:true}));
  await type(seller.page,seller.page.getByLabel(/物流单号/),'A11Y-'+Date.now());
  for(const label of ['商品凭证','包装凭证']){
   const input=seller.page.getByLabel(new RegExp('^'+label));await tabTo(seller.page,input);
   // Headless browsers cannot drive the operating-system file picker.
   await input.setInputFiles(picture);await expect(seller.page.getByAltText(label+'预览')).toBeVisible();
  }
  await scan(seller.page,'发货凭证弹窗',info);
  await press(seller.page,seller.page.getByRole('button',{name:'确认发货',exact:true}));
  await expect(seller.page.getByRole('dialog')).toBeHidden();
  await expect(buyer.page.getByRole('button',{name:'确认收货',exact:true})).toBeVisible({timeout:16000});
  await press(buyer.page,buyer.page.getByRole('button',{name:'确认收货',exact:true}));
  await press(buyer.page,buyer.page.getByRole('dialog').getByRole('button',{name:'确认收货',exact:true}));
  await expect(buyer.page.getByText('已确认收货。')).toBeVisible();
  await scan(buyer.page,'已完成订单及评价表单',info);
  await tabTo(buyer.page,buyer.page.getByRole('radio',{name:'1 星',exact:true}));
  for(let n=0;n<4;n++)await buyer.page.keyboard.press('ArrowRight');
  await expect(buyer.page.getByRole('radio',{name:'5 星',exact:true})).toBeChecked();
  await type(buyer.page,buyer.page.getByLabel(/^评价内容/),'商品符合描述，交易顺利。');
  await press(buyer.page,buyer.page.getByRole('button',{name:'提交评价',exact:true}));
  await scan(buyer.page,'评价确认弹窗',info);
  await press(buyer.page,buyer.page.getByRole('dialog').getByRole('button',{name:'确认提交',exact:true}));
  await expect(buyer.page.getByRole('region',{name:'我的评价'})).toContainText('待公开');
  const cdp=await buyer.context.newCDPSession(buyer.page);const tree=await cdp.send('Accessibility.getFullAXTree');await cdp.detach();
  const treePath=info.outputPath('订单真实可访问树.json');await writeFile(treePath,JSON.stringify(tree,null,2));
  await info.attach('订单真实可访问树.json',{path:treePath,contentType:'application/json'});
  await buyer.page.screenshot({path:info.outputPath('completed-order.png'),fullPage:true});
  const record=await(await buyer.page.request.get('/api/v1'+orderPath)).json();
  expect(record.status).toBe('COMPLETED');expect(record.fundsStatus).toBe('SETTLED');
 }finally{await Promise.all(contexts.map(x=>x.context.close()))}
});

test('手机导航选择页面后焦点进入页面且背景恢复可操作',async({page})=>{
 await page.setViewportSize({width:390,height:844});await page.goto('/');
 await page.getByRole('button',{name:'展开导航'}).click();
 const dialog=page.getByRole('dialog',{name:'主导航'});
 await tabTo(page,dialog.getByRole('link',{name:'登录',exact:true}));await page.keyboard.press('Enter');
 await expect(dialog).toBeHidden();await expect(page.getByRole('heading',{level:1})).toBeFocused();
 await tabTo(page,page.getByLabel('用户名',{exact:true}));await page.keyboard.insertText('buyer');
 await expect(page.getByLabel('用户名',{exact:true})).toHaveValue('buyer');
});
