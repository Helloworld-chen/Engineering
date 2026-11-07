<script setup lang="ts">
import {ref,watch} from 'vue';
import {useRoute,useRouter} from 'vue-router';
import {api,ApiError,money,type Product} from '../api/client';
import {useLoad} from '../lib/load';
import {useAction} from '../lib/action';
import {useFieldErrors} from '../lib/fieldErrors';
import {label} from '../lib/labels';
import {useSession} from '../stores/session';
import UploadField from '../components/UploadField.vue';
import PageState from '../components/PageState.vue';
const route=useRoute(),router=useRouter(),session=useSession();
const id=route.params.id?encodeURIComponent(String(route.params.id)):'';
const {data,loading,error,reload}=useLoad(()=>id?api<Product>('/products/'+id):Promise.resolve(null));
const {busy,error:actionError,run}=useAction();
const {fields,clear,flag,attributes}=useFieldErrors();
const conflict=ref(false),review=ref(false),latest=ref<Product>();
const recover=useAction();
const editable=(product:Product)=>product.sellerId===session.user?.id&&['AVAILABLE','OFF_SHELF'].includes(product.saleStatus);
const title=ref(''),description=ref(''),category=ref(''),price=ref(''),images=ref<string[]>([]),uploading=ref(false);
let version:number|undefined;
watch(data,product=>{
 if(!product||version!==undefined)return;
 title.value=product.title;description.value=product.description;category.value=product.category;price.value=(product.price/100).toFixed(2);images.value=[...product.images];version=product.version;
});
function submit(){
 clear();
 actionError.value='';
 const cents=Math.round(Number(price.value)*100);
 if(!title.value.trim())return flag('title','请填写商品名称');
 if(!category.value.trim())return flag('category','请填写商品分类');
 if(!/^\d+(\.\d{1,2})?$/.test(price.value)||cents<=0||cents>100000000)return flag('price','价格须大于零且不超过 100 万元，最多两位小数');
 if(!description.value.trim())return flag('description','请填写商品说明');
 if(!images.value.length)return flag('product-photos','请上传至少一张商品照片');
 void run(async()=>{
  try{
   await api(id?'/products/'+id:'/products',id?'PATCH':'POST',{title:title.value.trim(),description:description.value.trim(),category:category.value.trim(),price:cents,images:images.value,version});
  }catch(failure){
   if(failure instanceof ApiError&&failure.code==='VERSION_CONFLICT'){
    conflict.value=true;
    throw new Error('商品版本已变化，请查看最新商品信息后继续提交');
   }
   throw failure;
  }
  await router.push({path:'/my/products',query:{saved:'1'}});
 });
}
function readLatest(){
 void recover.run(async()=>{
  latest.value=await api<Product>('/products/'+id);
  review.value=true;
 });
}
function keepDraft(){
 if(!latest.value||!editable(latest.value))return;
 version=latest.value.version;
 conflict.value=false;
 actionError.value='';
 review.value=false;
}
</script>
<template>
 <RouterLink class="back-link" to="/my/products">← 返回我的商品</RouterLink>
 <div class="page-heading"><div><h1>{{id?'编辑商品':'发布商品'}}</h1><p>商品通过审核后会展示在集市。编辑商品后需重新审核。</p></div></div>
 <PageState :loading="loading" :error="error" @retry="reload">
  <p v-if="data&&!editable(data)" class="panel error" role="alert">当前商品无法编辑。</p>
  <el-form v-else class="panel editor form-stack stack" label-position="top" @submit.prevent="submit">
   <el-form-item label="商品名称" for="title" required><el-input id="title" v-model="title" v-bind="attributes('title')" maxlength="120" required/><p v-if="fields.title" id="title-error" class="error">{{fields.title}}</p></el-form-item>
   <div class="form-columns">
    <label class="field-label">商品分类 <span class="required">必填</span><input id="category" v-model="category" v-bind="attributes('category')" list="categories" maxlength="40" required/><span v-if="fields.category" id="category-error" class="error">{{fields.category}}</span><datalist id="categories"><option v-for="item in ['数码','图书','生活','运动','其他']" :key="item" :value="item"/></datalist></label>
    <el-form-item label="价格（元）" for="price" required><el-input id="price" v-model="price" v-bind="attributes('price')" inputmode="decimal" required/><p v-if="fields.price" id="price-error" class="error">{{fields.price}}</p></el-form-item>
   </div>
   <el-form-item label="商品说明" for="description" required><el-input id="description" v-model="description" v-bind="attributes('description')" type="textarea" :rows="5" maxlength="5000" required/><p v-if="fields.description" id="description-error" class="error">{{fields.description}}</p></el-form-item>
   <UploadField v-model="images" purpose="PRODUCT" label="商品照片" input-id="product-photos" :field-error="images.length?undefined:fields['product-photos']" @busy="uploading=$event"/>
   <p v-if="actionError" class="error" role="alert">{{actionError}}</p>
   <div v-if="conflict" class="stack notice">
    <p>编辑草稿已保留。</p>
    <p v-if="recover.error.value" class="error" role="alert">{{recover.error.value}}</p>
    <div><button class="button" type="button" :disabled="recover.busy.value" @click="readLatest">查看最新商品信息</button></div>
   </div>
   <div class="row end"><RouterLink class="button" to="/my/products">取消</RouterLink><button class="button primary" type="submit" :disabled="busy||uploading||conflict">{{busy?'正在提交…':'提交审核'}}</button></div>
  </el-form>
 </PageState>
 <el-dialog v-model="review" title="最新商品信息" width="min(560px, calc(100% - 32px))">
  <div v-if="latest" class="stack">
   <dl class="facts"><dt>名称</dt><dd>{{latest.title}}</dd><dt>分类</dt><dd>{{latest.category}}</dd><dt>价格</dt><dd>{{money(latest.price)}}</dd><dt>审核结果</dt><dd>{{label(latest.auditStatus)}}</dd><dt>销售状态</dt><dd>{{label(latest.saleStatus)}}</dd><dt>商品说明</dt><dd class="preserve">{{latest.description}}</dd></dl>
   <div v-if="latest.images.length" class="photo-strip"><img v-for="(image,index) in latest.images" :key="image" :src="'/api/v1/files/'+image" :alt="'最新商品照片 '+(index+1)"/></div>
   <p v-if="editable(latest)">继续后，表单会保留当前草稿。再次提交会用草稿内容覆盖以上商品资料，并重新送审。</p>
   <p v-else class="error">商品当前无法编辑，草稿仍保留在本页。</p>
   <div class="row end"><button type="button" class="button" @click="review=false">返回</button><button v-if="editable(latest)" type="button" class="button primary" @click="keepDraft">保留草稿并使用最新版本</button></div>
  </div>
 </el-dialog>
</template>
