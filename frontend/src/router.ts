import {createRouter,createWebHistory} from 'vue-router';
import {useSession} from './stores/session';
import {homeFor,canVisit} from './lib/access';
const router=createRouter({history:createWebHistory(),scrollBehavior:(to,from)=>to.path===from.path&&to.path.startsWith('/users/')?false:{top:0},routes:[
 {path:'/admin',component:()=>import('./views/StaffDashboard.vue'),meta:{title:'管理员工作台',role:'ADMIN'}},
 {path:'/support',component:()=>import('./views/StaffDashboard.vue'),meta:{title:'客服工作台',role:'SUPPORT'}},
 {path:'/moderation/:id',component:()=>import('./views/ModerationDetail.vue'),meta:{title:'商品审核详情',role:'ADMIN'}},
 {path:'/',component:()=>import('./views/CatalogView.vue'),meta:{title:'商品集市'}},
 {path:'/login',component:()=>import('./views/AuthView.vue'),meta:{title:'登录校园集市'}},{path:'/register',component:()=>import('./views/AuthView.vue'),meta:{title:'注册校园集市'}},
 {path:'/products/:id',component:()=>import('./views/ProductView.vue'),meta:{title:'商品详情'}},
 {path:'/publish',component:()=>import('./views/ProductEditor.vue'),meta:{title:'发布商品',role:'USER'}},
 {path:'/products/:id/edit',component:()=>import('./views/ProductEditor.vue'),meta:{title:'编辑商品',role:'USER'}},
 {path:'/my/products',component:()=>import('./views/MyProductsView.vue'),meta:{title:'我的商品',role:'USER'}},
 {path:'/orders',component:()=>import('./views/OrdersView.vue'),meta:{title:'我的订单',auth:true}},
 {path:'/orders/:id',component:()=>import('./views/OrderView.vue'),meta:{title:'订单详情',auth:true}},
 {path:'/cases',component:()=>import('./views/CasesView.vue'),meta:{title:'售后记录',auth:true}},
 {path:'/cases/:id',component:()=>import('./views/CaseView.vue'),meta:{title:'售后详情',auth:true}},
 {path:'/moderation',component:()=>import('./views/ModerationView.vue'),meta:{title:'商品审核',role:'ADMIN'}},
 {path:'/reports',component:()=>import('./views/ReportsView.vue'),meta:{title:'举报记录',auth:true}},
 {path:'/notifications',component:()=>import('./views/NotificationsView.vue'),meta:{title:'通知',auth:true}},
 {path:'/users/:id',component:()=>import('./views/UserProfileView.vue'),meta:{title:'用户资料',auth:true}},
 {path:'/profile',component:()=>import('./views/ProfileView.vue'),meta:{title:'个人中心',auth:true}},
 {path:'/:pathMatch(.*)*',component:()=>import('./views/NotFound.vue'),meta:{title:'页面未找到'}}
]});
router.beforeEach(async to=>{
 const session=useSession();
 if(!session.ready){
  try{await session.refresh()}catch{ /* 公共页面仍可访问。 */ }
 }
 if((to.meta.auth||to.meta.role)&&!session.user)return{path:'/login',query:{redirect:to.fullPath}};
 if(session.user&&!canVisit(session.user.role,to.path))return homeFor(session.user.role);
 if(to.meta.role&&session.user?.role!==to.meta.role)return homeFor(session.user?.role);
 return true;
});
export default router;
