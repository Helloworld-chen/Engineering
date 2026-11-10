import {test,expect,type Page} from '@playwright/test';
import {order,aftersale} from '../tests/sellerFixtures';
async function login(page:Page,user:string,path='/login'){
 await page.goto(path);await page.getByLabel('用户名',{exact:true}).fill(user);await page.getByLabel('密码',{exact:true}).fill('Campus123!');await page.getByRole('button',{name:'登录',exact:true}).click();
}
for(const role of ['admin','support'])test(`${role}岗位首页、路由隔离及桌面手机导航`,async({page},info)=>{
 await login(page,role);await expect(page).toHaveURL(new RegExp('/'+role+'$'));
 const heading=role==='admin'?'管理员工作台':'客服工作台';await expect(page.getByRole('heading',{name:heading,exact:true})).toBeVisible();
 await expect(page.getByRole('link',{name:'浏览商品',exact:true})).toHaveCount(0);await expect(page.locator('.balance')).toHaveCount(0);
 for(const width of [1440,1280,390]){
  await page.setViewportSize({width,height:900});await expect(page.getByRole('link',{name:'通知',exact:true})).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  await page.screenshot({path:info.outputPath(role+'-'+width+'.png'),fullPage:true});
 }
 const toggle=page.getByRole('button',{name:'展开导航'});await toggle.click();const drawer=page.getByRole('dialog');await expect(drawer).toBeVisible();
 await expect.poll(()=>drawer.evaluate(el=>el.contains(document.activeElement))).toBe(true);
 await page.keyboard.press('Escape');await expect(drawer).toBeHidden();await expect(toggle).toBeFocused();
 for(const path of role==='admin'?['/','/support','/cases','/Cases','/cases/','/orders','/Orders/','/publish']:['/','/admin','/moderation','/reports','/Reports/','/orders','/Orders','/orders/','/publish']){
  await page.goto(path);await expect(page).toHaveURL(new RegExp('/'+role+'$'));
 }
 await page.goto('/profile');await expect(page.getByRole('heading',{name:'账号资料',exact:true,level:1})).toBeVisible();await expect(page.getByText('模拟余额',{exact:true})).toHaveCount(0);
 if(role==='support')await expect(page.getByRole('link',{name:/我的举报/})).toHaveCount(0);
 await page.setViewportSize({width:1440,height:900});await page.getByRole('button',{name:'退出',exact:true}).click();await expect(page).toHaveURL(/\/$/);
 await login(page,role==='admin'?'support':'admin');await expect(page).toHaveURL(new RegExp('/'+(role==='admin'?'support':'admin')+'$'));
});
test('岗位登录回跳保留合法目标，拒绝无权目标',async({page})=>{
 await login(page,'admin','/reports?status=RESOLVED&page=2');await expect(page).toHaveURL(/\/reports\?status=RESOLVED&page=2$/);
 await page.getByRole('button',{name:'退出',exact:true}).click();
 await login(page,'support','/Orders/');await expect(page).toHaveURL(/\/support$/);
});
test('客服列表搜索分页及详情往返，管理员部分待办失败可重试',async({page})=>{
 const createdAt='2026-11-09T00:00:00Z';
 const cases=Array.from({length:25},(_,i)=>({id:'case-'+String(i).padStart(2,'0'),orderId:'order-'+i,buyerId:'buyer',kind:'REFUND',reason:'等待核对退款材料',status:'SUPPORT_REVIEW',createdAt,decision:null,deadline:null}));
 await page.route('**/api/v1/cases',r=>r.fulfill({json:{items:cases}}));
 await login(page,'support');await expect(page).toHaveURL(/\/support$/);await expect(page.locator('.work-stat').first()).toContainText('25');
 await page.goto('/cases?group=pending&kind=REFUND&page=2');await expect(page.locator('main .record')).toHaveCount(5);
 const item=cases[20];await page.route('**/api/v1/orders/'+item.orderId,r=>r.fulfill({json:order(item.orderId,{cases:[{...aftersale('SUPPORT_REVIEW'),...item}],status:'AWAITING_SHIPMENT',fundsStatus:'HELD'})}));
 await page.locator('main .record').first().click();await page.getByRole('link',{name:'查看订单与物流凭证'}).click();await expect(page.getByText('我的余额',{exact:false})).toHaveCount(0);
 await page.getByRole('link',{name:'← 返回售后详情'}).click();await page.getByRole('link',{name:'查看订单与物流凭证'}).click();await page.getByRole('link',{name:'退款详情 →'}).click();await page.getByRole('link',{name:'← 返回售后处理'}).click();await expect(page).toHaveURL(/\/cases\?group=pending&kind=REFUND&page=2$/);
 await page.getByLabel('搜索售后').fill('case-00');await expect(page.locator('main .record')).toHaveCount(1);await expect(page).toHaveURL(/page=1/);
 await page.getByRole('button',{name:'退出',exact:true}).click();
 let failure=true;await page.route('**/api/v1/reports',r=>failure?r.fulfill({status:503,json:{message:'举报读取失败'}}):r.continue());
 await login(page,'admin');await expect(page.getByRole('alert')).toContainText('举报读取失败');await expect(page.locator('.work-stat').nth(1).locator('strong')).toHaveText('—');
 failure=false;await page.getByRole('button',{name:'重新读取',exact:true}).click();await expect(page.getByRole('alert')).toHaveCount(0);
});
