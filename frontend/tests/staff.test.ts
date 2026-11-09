import {expect,it} from 'vitest';
import {homeFor,canVisit,returnTo} from '../src/lib/access';
import {ordered,pageItems,caseGroup} from '../src/lib/worklist';
it('角色首页和页面访问按岗位限定，关联详情仍可访问',()=>{
 expect(homeFor('ADMIN')).toBe('/admin');expect(homeFor('SUPPORT')).toBe('/support');expect(homeFor('USER')).toBe('/');
 expect(canVisit('ADMIN','/')).toBe(false);expect(canVisit('SUPPORT','/')).toBe(false);
 expect(canVisit('ADMIN','/cases/123')).toBe(false);expect(canVisit('SUPPORT','/reports')).toBe(false);
 expect(canVisit('ADMIN','/orders/123')).toBe(false);expect(canVisit('SUPPORT','/orders')).toBe(false);
 expect(canVisit('SUPPORT','/orders/123')).toBe(true);expect(canVisit('ADMIN','/products/123')).toBe(true);
 expect(canVisit('USER','/moderation')).toBe(false);expect(canVisit('USER','/products')).toBe(true);
});
it('返回地址限定站内且符合角色与允许路径，保留列表参数',()=>{
 expect(returnTo('/reports?status=RESOLVED&page=2','ADMIN','/admin',['/reports'])).toBe('/reports?status=RESOLVED&page=2');
 for(const value of ['//evil.test','/\\evil.test','https://evil.test','/cases/123'])expect(returnTo(value,'ADMIN','/admin',['/reports'])).toBe('/admin');
});
it('待办按时间与编号稳定排序，20条分页，售后分组互斥',()=>{
 const rows=Array.from({length:42},(_,i)=>({id:String(i).padStart(2,'0'),createdAt:'2026-11-09T00:00:00Z'}));
 expect(ordered(rows,true)[0].id).toBe('00');expect(ordered(rows,false)[0].id).toBe('41');
 expect(pageItems(rows,2).items.map(x=>x.id)).toEqual(rows.slice(20,40).map(x=>x.id));
 expect(pageItems(rows,99).page).toBe(3);expect(pageItems([],2).page).toBe(1);
 expect(caseGroup('SUPPORT_REVIEW')).toBe('pending');expect(caseGroup('CLOSED')).toBe('closed');expect(caseGroup('SELLER_REVIEW')).toBe('active');
});
it('Vue Router可匹配的大小写和尾斜线变体保持相同岗位权限',()=>{
 for(const path of ['/orders','/orders/','/Orders','/ORDERS/'])expect(canVisit('SUPPORT',path),path).toBe(false);
 for(const path of ['/reports','/reports/','/Reports/'])expect(canVisit('SUPPORT',path),path).toBe(false);
 for(const path of ['/cases','/cases/','/Cases','/CASES/case-1'])expect(canVisit('ADMIN',path),path).toBe(false);
 expect(canVisit('SUPPORT','/ORDERS/order-1/')).toBe(true);
});
