import type {Product,Order,Case} from '../src/api/client';
export function product(id='p',extra:Partial<Product>={}):Product {
 return {id,sellerId:'seller',title:'校园笔记本 '+id,description:'说明',category:'图书',price:100,images:[],auditStatus:'APPROVED',saleStatus:'AVAILABLE',version:0,createdAt:'2026-11-01T00:00:00.000Z',...extra};
}
export function order(id='o',extra:Partial<Order>={}):Order {
 return {id,productId:'p',buyerId:'buyer',sellerId:'seller',buyerName:'陈同学',sellerName:'林同学',amount:100,status:'PENDING_PAYMENT',closeReason:null,createdAt:'2026-11-02T00:00:00.000Z',payDeadline:null,receiptDeadline:null,receiptRemaining:null,receiptPaused:false,shippingBlocked:false,settlementBlocked:false,paymentGeneration:0,receiptGeneration:0,version:0,product:product(),serverTime:'2026-11-02T00:00:00.000Z',fundsStatus:'UNPAID',shipments:[],cases:[],allowedActions:[],...extra};
}
export function aftersale(status:string):Case {
 return {id:'case',orderId:'o',buyerId:'buyer',kind:'REFUND',reason:'退款',status,decision:null,decisionReason:null,deadline:null,generation:0,createdAt:'2026-11-02T00:00:00.000Z',version:0};
}
