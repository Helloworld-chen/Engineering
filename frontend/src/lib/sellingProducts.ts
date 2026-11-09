import type {Product,Order,Case} from '../api/client';
import {label} from './labels';

type Progress={message:string,label:string,to:string,priority:number};
export type SellingProduct={product:Product,order?:Order,progress?:Progress};
const compareId=(a:string,b:string)=>a<b?1:a>b?-1:0;
const newest=(a:{createdAt:string|null,id:string},b:{createdAt:string|null,id:string})=>
 (Date.parse(b.createdAt||'')||0)-(Date.parse(a.createdAt||'')||0)||compareId(a.id,b.id);
const activeCase=(c:Case)=>!['CLOSED','REFUNDED'].includes(c.status);

function progress(order:Order):Progress {
 const result:Progress={message:label(order.status),label:'查看订单',to:'/orders/'+order.id,priority:2};
 if(order.fundsStatus==='REFUNDED')return {...result,message:'订单已退款',priority:3};
 if(order.status==='COMPLETED')return {...result,message:'交易已完成',priority:3};
 if(order.status==='CLOSED')return {...result,message:order.closeReason==='CANCELLED'?'最近订单已由买家取消':order.closeReason==='PAYMENT_EXPIRED'?'最近订单付款超时，已关闭':'最近订单已关闭',label:'查看历史订单',priority:3};

 const currentCase=order.cases.filter(activeCase).sort(newest)[0];
 if(currentCase){
  const action=currentCase.status==='SELLER_REVIEW'?'待你审核退款':currentCase.status==='RETURN_INSPECTION'?'待你验收退货':undefined;
  return {...result,message:action||label(currentCase.status),label:action?'处理售后':'查看售后',to:'/cases/'+currentCase.id,priority:action?0:2};
 }
 if(order.status==='PENDING_PAYMENT')return {...result,message:'买家已下单，等待付款',priority:1};
 if(order.status==='AWAITING_SHIPMENT'){
  if(order.allowedActions.includes('SHIP')&&!order.shippingBlocked)return {...result,message:'买家已付款，待你发货',label:'去发货',priority:0};
  return {...result,message:'订单暂不可发货，请查看订单进度'};
 }
 if(order.status==='IN_TRANSIT')return {...result,message:'商品运输中'};
 if(order.status==='DELIVERED')return {...result,message:'等待买家收货'};
 return result;
}

export function sellingProducts(products:Product[],orders:Order[]):SellingProduct[]{
 const selected=new Map<string,Order>();
 // A cancelled attempt must never hide a subsequent active purchase.
 for(const order of [...orders].sort((a,b)=>Number(a.status==='CLOSED')-Number(b.status==='CLOSED')||newest(a,b))){
  if(!selected.has(order.productId))selected.set(order.productId,order);
 }
 return products.map(product=>{
  const order=selected.get(product.id);
  return {product,order,progress:order?progress(order):undefined};
 }).sort((a,b)=>(a.progress?.priority??3)-(b.progress?.priority??3)||newest(a.product,b.product));
}
