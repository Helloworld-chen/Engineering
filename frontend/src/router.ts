import {createRouter,createWebHistory} from 'vue-router';
import {useSession} from './stores/session';
const router=createRouter({history:createWebHistory(),scrollBehavior:()=>({top:0}),routes:[
 {path:'/',component:()=>import('./views/CatalogView.vue')},
 {path:'/login',component:()=>import('./views/AuthView.vue')},{path:'/register',component:()=>import('./views/AuthView.vue')},
 {path:'/products/:id',component:()=>import('./views/ProductView.vue')},
 {path:'/publish',component:()=>import('./views/ProductEditor.vue'),meta:{role:'USER'}},
 {path:'/products/:id/edit',component:()=>import('./views/ProductEditor.vue'),meta:{role:'USER'}},
 {path:'/my/products',component:()=>import('./views/MyProductsView.vue'),meta:{role:'USER'}},
 {path:'/orders',component:()=>import('./views/OrdersView.vue'),meta:{auth:true}},
 {path:'/orders/:id',component:()=>import('./views/OrderView.vue'),meta:{auth:true}},
 {path:'/cases',component:()=>import('./views/CasesView.vue'),meta:{auth:true}},
 {path:'/cases/:id',component:()=>import('./views/CaseView.vue'),meta:{auth:true}},
 {path:'/moderation',component:()=>import('./views/ModerationView.vue'),meta:{role:'ADMIN'}},
 {path:'/reports',component:()=>import('./views/ReportsView.vue'),meta:{auth:true}},
 {path:'/notifications',component:()=>import('./views/NotificationsView.vue'),meta:{auth:true}},
 {path:'/profile',component:()=>import('./views/ProfileView.vue'),meta:{auth:true}},
 {path:'/:pathMatch(.*)*',component:()=>import('./views/NotFound.vue')}
]});
router.beforeEach(async to=>{
 const session=useSession();
 if(!session.ready){
  try{await session.refresh()}catch{ /* 公共页面仍可访问。 */ }
 }
 if((to.meta.auth||to.meta.role)&&!session.user)return{path:'/login',query:{redirect:to.fullPath}};
 if(to.meta.role&&session.user?.role!==to.meta.role)return'/';
 return true;
});
export default router;
