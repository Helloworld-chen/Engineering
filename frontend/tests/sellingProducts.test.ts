import {describe,expect,it} from 'vitest';
import {sellingProducts} from '../src/lib/sellingProducts';

import {product,order,aftersale} from './sellerFixtures';

describe('卖家商品交易进度',()=>{
 it('关联未关闭订单，忽略其他商品并保留未成交商品',()=>{
  const rows=sellingProducts([product(),product('unsold')],[order('closed',{status:'CLOSED',closeReason:'CANCELLED',createdAt:'2026-11-03T00:00:00.000Z'}),order(),order('other',{productId:'other'})]);
  expect(rows[0].progress).toMatchObject({message:'买家已下单，等待付款',label:'查看订单',to:'/orders/o'});
  expect(rows[1].progress).toBeUndefined();
 });
 it('历史订单按创建时间和编号选最新，关闭原因明确',()=>{
  const progress=sellingProducts([product()],[order('a',{status:'CLOSED',closeReason:'CANCELLED'}),order('z',{status:'CLOSED',closeReason:'PAYMENT_EXPIRED'})])[0].progress;
  expect(progress).toMatchObject({message:'最近订单付款超时，已关闭',label:'查看历史订单',to:'/orders/z'});
 });
 it.each([
  ['PENDING_PAYMENT','UNPAID',[],'买家已下单，等待付款','查看订单'],
  ['AWAITING_SHIPMENT','HELD',['SHIP'],'买家已付款，待你发货','去发货'],
  ['IN_TRANSIT','HELD',[],'商品运输中','查看订单'],
  ['DELIVERED','HELD',[],'等待买家收货','查看订单'],
  ['COMPLETED','SETTLED',[],'交易已完成','查看订单'],
  ['CLOSED','REFUNDED',[],'订单已退款','查看订单'],
 ] as const)('%s 展示准确进度和入口',(status,fundsStatus,actions,message,label)=>{
  expect(sellingProducts([product()],[order('o',{status,fundsStatus,allowedActions:[...actions]})])[0].progress).toMatchObject({message,label,to:'/orders/o'});
 });
 it.each([
  ['SELLER_REVIEW','待你审核退款','处理售后',0],
  ['RETURN_INSPECTION','待你验收退货','处理售后',0],
  ['SUPPORT_REVIEW','客服处理中','查看售后',2],
  ['BUYER_APPEAL','等待买家申诉','查看售后',2],
  ['RETURN_REQUIRED','待买家寄回','查看售后',2],
 ])('售后 %s 优先于发货提示',(status,message,label,priority)=>{
  expect(sellingProducts([product()],[order('o',{status:'AWAITING_SHIPMENT',fundsStatus:'HELD',shippingBlocked:true,cases:[aftersale(status)]})])[0].progress).toMatchObject({message,label,priority,to:'/cases/case'});
 });
 it('只在后端允许且未阻止发货时显示去发货',()=>{
  for(const extra of [{allowedActions:[]},{allowedActions:['SHIP'],shippingBlocked:true}]){
   expect(sellingProducts([product()],[order('o',{status:'AWAITING_SHIPMENT',...extra})])[0].progress?.label).toBe('查看订单');
  }
 });
 it('优先待操作，其次待付款和其他进行中，同组按发布时间及编号倒序',()=>{
  const products=['plain','transit','pay','ship','case','z'].map(id=>product(id));
  const orders=[order('t',{productId:'transit',status:'IN_TRANSIT'}),order('p',{productId:'pay'}),order('s',{productId:'ship',status:'AWAITING_SHIPMENT',allowedActions:['SHIP']}),order('c',{productId:'case',cases:[aftersale('SELLER_REVIEW')]}),order('z',{productId:'z',status:'COMPLETED'})];
  expect(sellingProducts(products,orders).map(r=>r.product.id)).toEqual(['ship','case','pay','transit','z','plain']);
  expect(products.map(p=>p.id)).toEqual(['plain','transit','pay','ship','case','z']);
 });
 it('已退款订单忽略已结束售后，保持商品销售状态',()=>{
  const rows=sellingProducts([product('p',{saleStatus:'SOLD'})],[order('o',{status:'CLOSED',fundsStatus:'REFUNDED',cases:[aftersale('RESOLVED')]})]);
  expect(rows[0].product.saleStatus).toBe('SOLD');
  expect(rows[0].progress?.message).toBe('订单已退款');
 });
});
