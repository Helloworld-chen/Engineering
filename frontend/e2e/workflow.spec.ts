import {test,expect,type Page,type Request,type TestInfo} from '@playwright/test';

const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII=','base64');
const picture = {name:'fixture.png',mimeType:'image/png',buffer:png};

async function login(page: Page, username: string) {
 await page.goto('/login');
 await page.getByLabel('用户名',{exact:true}).fill(username);
 await page.getByLabel('密码',{exact:true}).fill('Campus123!');
 await page.getByRole('button',{name:'登录',exact:true}).click();
 await expect(page).toHaveURL(/\/$/);
}

async function photograph(page: Page, info: TestInfo, name: string) {
 for(const width of [1440,1280,390]){
  await page.setViewportSize({width,height:900});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  await page.screenshot({path:info.outputPath(name+'-'+width+'.png'),fullPage:true});
 }
 await page.setViewportSize({width:1440,height:900});
}

async function ship(page: Page, action: '发货'|'寄出退货', tracking: string) {
 await page.getByRole('button',{name:action,exact:true}).click();
 const dialog=page.getByRole('dialog',{name:action});
 await dialog.getByLabel(/物流单号/).fill(tracking);
 if(action==='发货'){
  await dialog.getByRole('button',{name:'确认发货'}).click();
  const field=dialog.getByLabel(/商品凭证/);
  await expect(field).toHaveAttribute('aria-invalid','true');
  await expect(field).toHaveAccessibleDescription(/请上传至少一张商品凭证/);
  await expect(field).toBeFocused();
 }
 await dialog.getByLabel(/商品凭证/).setInputFiles(picture);
 await expect(dialog.getByAltText('商品凭证预览')).toBeVisible();
 await dialog.getByLabel(/包装凭证/).setInputFiles(picture);
 await expect(dialog.getByAltText('包装凭证预览')).toBeVisible();
 await dialog.getByRole('button',{name:'确认'+action}).click();
 await expect(dialog).toBeHidden();
}

test('发布审核、下单付款、投诉与退货验收',async({browser,baseURL},info)=>{
 test.setTimeout(90000);
 const contexts=await Promise.all(['seller','admin','buyer','support'].map(async username=>{
  const context=await browser.newContext({baseURL,viewport:{width:1440,height:900}});
  const page=await context.newPage();
  await login(page,username);
  return {context,page};
 }));
 const [seller,admin,buyer,support]=contexts.map(item=>item.page);
 const title='流程验收 · 校园随身笔记本 '+Date.now();
 try{
  await seller.goto('/publish');
  await seller.getByLabel('商品名称',{exact:true}).fill(title);
  await seller.getByLabel(/商品分类/).fill('图书');
  await seller.getByLabel('价格（元）',{exact:true}).fill('1.00');
  await seller.getByLabel('商品说明',{exact:true}).fill('全新横线笔记本，课程演示验收商品。');
  await seller.getByLabel(/商品照片/).setInputFiles(picture);
  await expect(seller.getByAltText('商品照片预览')).toBeVisible();
  await seller.getByRole('button',{name:'提交审核'}).click();
  await expect(seller.getByRole('status')).toContainText('商品已提交审核');
  const productPath=(await seller.getByRole('link',{name:title,exact:true}).getAttribute('href'))!;

  await admin.goto('/moderation');
  await admin.getByRole('article').filter({hasText:title}).getByRole('button',{name:'通过',exact:true}).click();
  await admin.getByLabel('审核理由').fill('课程演示商品，资料齐全');
  await admin.getByRole('button',{name:'确认通过审核'}).click();
  await expect(admin.getByRole('status')).toContainText('审核结果已保存');

  await buyer.goto('/');
  await buyer.getByLabel('商品关键词').fill(title);
  await buyer.getByRole('button',{name:'搜索',exact:true}).click();
  await buyer.getByRole('link',{name:new RegExp(title)}).click();
  await expect(buyer).toHaveURL(new RegExp(productPath+'$'));
  await expect(buyer.getByRole('heading',{name:title,exact:true})).toBeVisible();
  await photograph(buyer,info,'product-detail');
  await buyer.getByRole('button',{name:'立即下单'}).click();
  await buyer.getByRole('button',{name:'确认下单'}).click();
  await expect(buyer.getByText('付款剩余时间')).toBeVisible();
  await photograph(buyer,info,'order-payment');
  const orderPath=new URL(buyer.url()).pathname;
  await buyer.getByRole('button',{name:'付款',exact:true}).click();
  await buyer.getByRole('button',{name:'确认付款'}).click();
  await expect(buyer.getByText('订单已付款，等待卖家发货。')).toBeVisible();

  await seller.goto(orderPath);
  await ship(seller,'发货','CAMPUS-OUTBOUND');
  await expect(buyer.getByRole('button',{name:'提交投诉'})).toBeVisible({timeout:16000});
  await buyer.getByRole('button',{name:'提交投诉'}).click();
  await buyer.getByLabel('申请理由').fill('商品外观与说明不符，申请退货');
  await buyer.getByRole('button',{name:'确认提交投诉'}).click();
  await expect(buyer.getByText('自动收货已暂停。')).toBeVisible();
  const casePath=new URL(buyer.url()).pathname;
  await photograph(buyer,info,'case-detail');
  await buyer.goto(orderPath);
  await expect(buyer.locator('.countdown')).toContainText('已暂停');
  const paused=await buyer.locator('.countdown').textContent();
  await buyer.reload();
  await expect(buyer.locator('.countdown')).toHaveText(paused!);

  await support.goto(casePath);
  await support.getByRole('button',{name:'批准退货',exact:true}).click();
  await support.getByLabel('处理理由').fill('同意退回商品并验收');
  await support.getByRole('button',{name:'确认批准退货'}).click();
  await expect(support.getByText('待买家寄回')).toBeVisible();

  await buyer.goto(orderPath);
  await ship(buyer,'寄出退货','CAMPUS-RETURN');
  await seller.goto(casePath);
  await expect(seller.getByRole('heading',{name:'售后详情',exact:true})).toBeVisible();
  await expect(seller.getByText('退货运输中',{exact:true})).toBeVisible();
  await seller.context().setOffline(true);
  await expect(buyer.getByText('退货预计送达')).toBeVisible();
  await expect(buyer.getByText('退货验收剩余时间')).toBeVisible({timeout:16000});
  await seller.context().setOffline(false);
  await expect(seller.getByRole('button',{name:'通过退货验收',exact:true})).toBeVisible({timeout:16000});
  await expect(seller.locator('.countdown')).toBeVisible();
  await seller.getByRole('button',{name:'通过退货验收',exact:true}).click();
  await seller.getByLabel('处理理由').fill('商品及包装验收无误');
  await seller.getByRole('button',{name:'确认通过退货验收'}).click();
  await buyer.goto(orderPath);
  await expect(buyer.getByRole('region',{name:'模拟货款'})).toContainText('已退款');
  await expect(buyer.getByText('CAMPUS-RETURN',{exact:true})).toBeVisible();
 }finally{
  await Promise.all(contexts.map(({context})=>context.close()));
 }
});

test('注册、资料与商品维护、举报和通知已读',async({browser,baseURL,page})=>{
 const adminContext=await browser.newContext({baseURL});
 const admin=await adminContext.newPage();
 const suffix=Date.now().toString();
 const username='tester_'+suffix,title='资料验收 · 宿舍收纳盒 '+suffix;
 try{
  await login(admin,'admin');
  await page.goto('/register');
  await page.getByLabel('用户名',{exact:true}).fill(username);
  await page.getByLabel('昵称',{exact:true}).fill('新同学');
  await page.getByLabel('密码',{exact:true}).fill('Campus123!');
  const confirmation=page.getByLabel('确认密码',{exact:true});
  await confirmation.fill('Campus123?');
  const registrationRequests:Request[]=[];
  page.on('request',request=>{
   if(request.method()==='POST'&&new URL(request.url()).pathname==='/api/v1/auth/register')registrationRequests.push(request);
  });
  await page.getByRole('button',{name:'注册',exact:true}).click();
  await expect(confirmation).toHaveAccessibleDescription('两次输入的密码不一致');
  expect(registrationRequests).toHaveLength(0);

  await confirmation.fill('Campus123!');
  await page.getByRole('button',{name:'注册',exact:true}).click();
  await expect(page.getByRole('link',{name:'个人中心 · 新同学'})).toBeVisible();
  expect(registrationRequests).toHaveLength(1);
  expect(registrationRequests[0].postDataJSON()).toEqual({username,password:'Campus123!',displayName:'新同学'});
  await page.getByRole('button',{name:'退出',exact:true}).click();
  await expect(page.getByRole('link',{name:'登录',exact:true})).toBeVisible();
  await login(page,username);
  await page.getByRole('link',{name:'个人中心 · 新同学'}).click();
  await page.getByLabel('昵称',{exact:true}).fill('收纳小组');
  await page.getByRole('button',{name:'保存昵称'}).click();
  await expect(page.getByRole('status')).toContainText('昵称已更新');
  await expect(page.getByRole('link',{name:'个人中心 · 收纳小组'})).toBeVisible();

  await page.goto('/publish');
  await page.getByLabel('商品名称',{exact:true}).fill(title);
  await page.getByLabel(/商品分类/).fill('生活');
  await page.getByLabel('价格（元）',{exact:true}).fill('12.50');
  await page.getByLabel('商品说明',{exact:true}).fill('透明收纳盒一只，无破损。');
  await page.getByLabel(/商品照片/).setInputFiles(picture);
  await expect(page.getByAltText('商品照片预览')).toBeVisible();
  await page.getByRole('button',{name:'提交审核'}).click();
  const row=page.getByRole('article').filter({hasText:title});
  await expect(row).toBeVisible();
  await row.getByRole('link',{name:'编辑',exact:true}).click();
  const description=page.getByLabel('商品说明',{exact:true});
  await expect(description).toHaveValue('透明收纳盒一只，无破损。');
  await description.fill('透明收纳盒一只，无破损，尺寸 28 × 20 厘米。');
  await page.getByRole('button',{name:'提交审核'}).click();
  await expect(row.getByText('待处理',{exact:true})).toBeVisible();

  await admin.goto('/moderation');
  const pending=admin.getByRole('article').filter({hasText:title});
  await expect(pending).toContainText('尺寸 28 × 20 厘米');
  await pending.getByRole('button',{name:'通过',exact:true}).click();
  await admin.getByLabel('审核理由').fill('已检查商品描述和照片');
  await admin.getByRole('button',{name:'确认通过审核'}).click();
  await expect(admin.getByRole('status')).toContainText('审核结果已保存');
  await expect(row.getByText('审核通过',{exact:true})).toBeVisible();
  await row.getByRole('button',{name:'下架',exact:true}).click();
  await expect(row.getByText('已下架',{exact:true})).toBeVisible();
  await row.getByRole('button',{name:'上架',exact:true}).click();
  await expect(row.getByText('在售',{exact:true})).toBeVisible();

  await page.goto('/products/demo-product-0');
  await page.getByRole('button',{name:'举报商品'}).click();
  await page.getByLabel('举报理由').fill('演示验收：检查商品照片信息 '+suffix);
  await page.getByRole('button',{name:'确认举报商品'}).click();
  await expect(page.getByRole('status')).toContainText('举报已提交');
  await page.goto('/reports');
  await expect(page.getByText('演示验收：检查商品照片信息 '+suffix)).toBeVisible();
  await admin.goto('/reports');
  await admin.getByRole('article').filter({hasText:suffix}).getByRole('button',{name:'处理举报'}).click();
  await admin.getByRole('textbox',{name:'处理结果',exact:true}).fill('核对完毕，演示举报已归档');
  await admin.getByRole('button',{name:'确认保存处理结果'}).click();
  await expect(admin.getByRole('status')).toContainText('举报处理结果已保存');
  await expect(page.getByText('处理结果 · 核对完毕，演示举报已归档')).toBeVisible();

  await page.getByRole('link',{name:'通知',exact:true}).click();
  await page.getByRole('button',{name:'未读通知'}).click();
  const notification=page.getByRole('article').filter({hasText:'举报已处理'});
  await notification.getByRole('button',{name:'标为已读'}).click();
  await expect(notification).toHaveCount(0);
  await page.getByRole('button',{name:'全部通知'}).click();
  await expect(notification.getByText(/· 已读/)).toBeVisible();
  // Keep generated listings out of the public catalog after the smoke test.
  await page.goto('/my/products');
  await row.getByRole('button',{name:'下架',exact:true}).click();
  await expect(row.getByText('已下架',{exact:true})).toBeVisible();
 }finally{
  await adminContext.close();
 }
});

test('商品版本变化时保留编辑草稿并显式应用最新版本',async({browser,baseURL,page})=>{
 const adminContext=await browser.newContext({baseURL});
 const admin=await adminContext.newPage();
 const title='商品版本验收 '+Date.now();
 try{
  await Promise.all([login(admin,'admin'),login(page,'seller')]);
  await page.goto('/publish');
  await page.getByLabel('商品名称',{exact:true}).fill(title);
  await page.getByLabel(/商品分类/).fill('生活');
  await page.getByLabel('价格（元）',{exact:true}).fill('1.00');
  await page.getByLabel('商品说明',{exact:true}).fill('原商品说明。');
  await page.getByLabel(/商品照片/).setInputFiles(picture);
  await expect(page.getByAltText('商品照片预览')).toBeVisible();
  await page.getByRole('button',{name:'提交审核'}).click();
  const row=page.getByRole('article').filter({hasText:title});
  await row.getByRole('link',{name:'编辑',exact:true}).click();
  const description=page.getByLabel('商品说明',{exact:true});
  await description.fill('编辑页保存的商品说明草稿。');

  await admin.goto('/moderation');
  await admin.getByRole('article').filter({hasText:title}).getByRole('button',{name:'通过',exact:true}).click();
  await admin.getByLabel('审核理由').fill('并发版本验证');
  await admin.getByRole('button',{name:'确认通过审核'}).click();
  await expect(admin.getByRole('status')).toContainText('审核结果已保存');

  await page.getByRole('button',{name:'提交审核'}).click();
  await expect(page.getByRole('alert')).toContainText('商品版本');
  await expect(description).toHaveValue('编辑页保存的商品说明草稿。');
  await page.getByRole('button',{name:'查看最新商品信息'}).click();
  await expect(page.getByRole('dialog')).toContainText('原商品说明。');
  await page.getByRole('button',{name:'保留草稿并使用最新版本'}).click();
  await expect(description).toHaveValue('编辑页保存的商品说明草稿。');
  await page.getByRole('button',{name:'提交审核'}).click();
  await expect(row.getByText('待处理',{exact:true})).toBeVisible();
  await row.getByRole('button',{name:'下架',exact:true}).click();
  await expect(row.getByText('已下架',{exact:true})).toBeVisible();
 }finally{
  await adminContext.close();
 }
});

test('商品表单错误关联字段并移动焦点',async({page})=>{
 await login(page,'seller');
 await page.goto('/publish');
 await page.getByLabel('商品名称',{exact:true}).fill('表单校验商品');
 await page.getByLabel(/商品分类/).fill('生活');
 const price=page.getByLabel('价格（元）',{exact:true});
 await price.fill('0');
 await page.getByLabel('商品说明',{exact:true}).fill('验证字段错误提示。');
 await page.getByRole('button',{name:'提交审核'}).click();
 await expect(price).toHaveAttribute('aria-invalid','true');
 await expect(price).toHaveAccessibleDescription(/价格须大于零/);
 await expect(price).toBeFocused();

 await price.fill('1.00');
 await page.getByRole('button',{name:'提交审核'}).click();
 const photo=page.getByLabel(/商品照片/);
 await expect(photo).toHaveAttribute('aria-invalid','true');
 await expect(photo).toHaveAccessibleDescription(/请上传至少一张商品照片/);
 await expect(photo).toBeFocused();
 await photo.setInputFiles({name:'oversize.png',mimeType:'image/png',buffer:Buffer.alloc(5000001)});
 await expect(photo).toHaveAccessibleDescription(/每张图片不能超过 5 MB/);
 await expect(photo).toBeFocused();
});
