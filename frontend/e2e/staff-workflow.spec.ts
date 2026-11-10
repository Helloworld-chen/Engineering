import {test,expect,type APIRequestContext} from '@playwright/test';
async function post(client:APIRequestContext,path:string,data:unknown,method='POST'){
 const {csrfToken}=await (await client.get('/api/v1/session')).json();
 const response=await client.fetch('/api/v1'+path,{method,headers:{'X-CSRF-TOKEN':csrfToken,'Idempotency-Key':crypto.randomUUID()},data});
 expect(response.ok(),await response.text()).toBeTruthy();return response.json();
}
test('岗位真实流程：审核冲突重核、举报返回、退款申诉客服裁决',async({browser,baseURL})=>{
 test.skip(process.env.STAFF_E2E_ISOLATED!=='1','仅在独立数据库执行');
 const contexts=await Promise.all(['seller','buyer','admin','support'].map(async username=>{
  const context=await browser.newContext({baseURL});await post(context.request,'/auth/login',{username,password:'Campus123!'});return context;
 }));
 const [seller,buyer,admin,support]=contexts;
 try{
  const title='岗位流程验收 '+crypto.randomUUID();
  const {csrfToken}=await (await seller.request.get('/api/v1/session')).json();
  const upload=await seller.request.post('/api/v1/files',{headers:{'X-CSRF-TOKEN':csrfToken},multipart:{purpose:'PRODUCT',file:{name:'book.png',mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII=','base64')}}});
  const fields={title,description:'用于岗位流程独立测试的课本',price:100,category:'图书',images:[(await upload.json()).id]};
  const product=await post(seller.request,'/products',fields);
  const page=await admin.newPage();await page.goto('/moderation?q='+encodeURIComponent(title));
  await page.getByRole('link',{name:'审核商品',exact:true}).click();await page.getByRole('button',{name:'通过',exact:true}).click();
  await post(seller.request,'/products/'+product.id,{...fields,description:'已补充说明：有少量笔记',version:product.version},'PATCH');
  await page.getByLabel('审核理由').fill('初次审核');await page.getByRole('button',{name:'确认通过审核'}).click();
  await expect(page.getByRole('dialog',{name:'通过审核'})).toBeHidden();await expect(page.locator('main').getByRole('alert')).toContainText('已重新读取');await expect(page.getByText('已补充说明：有少量笔记',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'通过',exact:true}).click();await page.getByLabel('审核理由').fill('复核新说明与照片通过');await page.getByRole('button',{name:'确认通过审核'}).click();
  await expect(page.locator('main').getByRole('status')).toContainText('审核结果已保存');await page.getByRole('link',{name:'← 返回商品审核'}).click();await expect(page).toHaveURL(new RegExp('/moderation\\?q='));
  const report=await post(buyer.request,'/reports',{productId:product.id,reason:'核对商品说明 '+title});
  const reportPath='/reports?status=PENDING&q='+report.id;
  await page.goto(reportPath);await page.getByRole('link',{name:'查看被举报商品 →'}).click();await page.getByRole('link',{name:'返回工作页面'}).click();await expect(page).toHaveURL(baseURL+reportPath);
  await page.getByRole('button',{name:'处理举报'}).click();await page.getByLabel('处理结果',{exact:true}).fill('已核实说明，记录处理结果');await page.getByRole('button',{name:'确认保存处理结果'}).click();await expect(page.locator('main').getByRole('status')).toContainText('举报处理结果已保存');
  const order=await post(buyer.request,'/orders',{productId:product.id});await post(buyer.request,'/orders/'+order.id+'/payment',{});
  const refund=await post(buyer.request,'/cases',{orderId:order.id,kind:'REFUND',reason:'需要申请退款'});await post(seller.request,'/cases/'+refund.id+'/actions',{action:'REJECT',reason:'请客服核实'});await post(buyer.request,'/cases/'+refund.id+'/actions',{action:'APPEAL',reason:'补充申诉'});
  const service=await support.newPage();await service.goto('/support');await service.getByRole('link',{name:'查看全部'}).click();await service.getByLabel('搜索售后').fill(refund.id);await service.locator('main .record').click();
  await service.getByRole('button',{name:'同意退款',exact:true}).click();await service.getByLabel('处理理由').fill('核实申请，批准退回货款');await service.getByRole('button',{name:'确认同意退款'}).click();await expect(service.locator('main').getByRole('status')).toContainText('售后处理结果已保存');
  const result=await (await buyer.request.get('/api/v1/orders/'+order.id)).json();expect(result.fundsStatus).toBe('REFUNDED');
  await service.getByRole('link',{name:'← 返回售后处理'}).click();await expect(service.locator('main .record')).toHaveCount(0);
 }finally{await Promise.all(contexts.map(c=>c.close()))}
});
