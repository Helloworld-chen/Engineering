"""Single source for public DTO schemas and endpoint descriptions. Regenerate with python3."""
import json
from pathlib import Path
S=lambda **kw: {'type':'string',**kw}
N=lambda **kw: {'type':'integer',**kw}
B={'type':'boolean'}
REF=lambda n:{'$ref':'#/components/schemas/'+n}
ARRAY=lambda t:{'type':'array','items':t}
OBJ=lambda props,required=None:{'type':'object','properties':props,'required':list(props) if required is None else required}
DATE={'type':['string','null'],'format':'date-time'}
schemas={
 'Actor':OBJ({'id':S(),'username':S(),'displayName':S(),'role':S(enum=['USER','ADMIN','SUPPORT'])}),
 'Product':OBJ({'id':S(),'sellerId':S(),'title':S(),'description':S(),'category':S(),'price':N(minimum=1),'images':ARRAY(S()),'auditStatus':S(),'saleStatus':S(),'version':N(),'createdAt':DATE,'sellerName':S()},['id','sellerId','title','description','category','price','images','auditStatus','saleStatus','version','createdAt']),
 'Case':OBJ({'id':S(),'orderId':S(),'buyerId':S(),'kind':S(),'reason':S(),'status':S(),'decision':{'type':['string','null']},'decisionReason':{'type':['string','null']},'deadline':DATE,'generation':N(),'createdAt':DATE,'version':N()}),
 'Shipment':OBJ({'id':S(),'orderId':S(),'direction':S(),'trackingNumber':S(),'evidence':OBJ({'goods':ARRAY(S()),'packaging':ARRAY(S())}),'status':S(),'shippedAt':DATE,'dueAt':DATE,'deliveredAt':DATE,'generation':N()}),
 'Order':OBJ({'id':S(),'productId':S(),'buyerId':S(),'sellerId':S(),'amount':N(),'status':S(),'closeReason':{'type':['string','null']},'createdAt':DATE,'payDeadline':DATE,'receiptDeadline':DATE,'receiptRemaining':{'type':['integer','null']},'receiptPaused':B,'shippingBlocked':B,'settlementBlocked':B,'paymentGeneration':N(),'receiptGeneration':N(),'version':N(),'product':REF('Product'),'serverTime':S(format='date-time'),'fundsStatus':S(),'shipments':ARRAY(REF('Shipment')),'cases':ARRAY(REF('Case')),'allowedActions':ARRAY(S())}),
 'Notification':OBJ({'id':S(),'recipientId':S(),'aggregateId':S(),'message':S(),'createdAt':DATE,'isRead':B}),
 'Report':OBJ({'id':S(),'productId':S(),'reporterId':S(),'reason':S(),'status':S(),'result':{'type':['string','null']},'createdAt':DATE}),
 'Error':OBJ({'code':S(),'message':S()}),
 'Session':OBJ({'user':{'anyOf':[REF('Actor'),{'type':'null'}]},'balance':{'type':['integer','null']},'csrfToken':S(),'demo':B}),
 'Success':OBJ({'success':B}),
 'Upload':OBJ({'id':S(),'url':S(),'purpose':S()}),
 'ProductInput':OBJ({'title':S(minLength=1,maxLength=120),'description':S(minLength=1,maxLength=5000),'category':S(),'price':N(minimum=1,maximum=100000000),'images':ARRAY(S()),'version':N()},['title','description','category','price','images']),
 'ShipInput':OBJ({'trackingNumber':S(minLength=1,maxLength=100),'goods':ARRAY(S()),'packaging':ARRAY(S())}),
}
paths={}
def add(path,method,summary,output,body=None,public=False,idem=True):
 p=[]
 for part in path.split('/'):
  if part.startswith('{'):p.append({'name':part[1:-1],'in':'path','required':True,'schema':S()})
 if method not in ('get',):
  p.append({'name':'X-CSRF-TOKEN','in':'header','required':True,'schema':S()})
  if idem:p.append({'name':'Idempotency-Key','in':'header','required':True,'schema':S(minLength=8,maxLength=100)})
 spec={'summary':summary,'operationId':method+'_'+path.replace('/','_').replace('{','').replace('}','').replace('-','_'),'security':[] if public else [{'session':[]}],'parameters':p,'responses':{'200':{'description':'成功','content':{'application/json':{'schema':output}}},**{str(code):{'description':desc,'content':{'application/json':{'schema':REF('Error')}}} for code,desc in [(400,'输入无效'),(401,'未登录'),(403,'无权限或CSRF失败'),(409,'状态、期限或请求冲突'),(503,'恢复中或数据库暂时不可用')]}}}
 if body is not None:spec['requestBody']={'required':True,'content':{'application/json':{'schema':body}}}
 paths.setdefault(path,{})[method]=spec
items=lambda name:OBJ({'items':ARRAY(REF(name))})
add('/session','get','当前会话与CSRF令牌',REF('Session'),public=True)
add('/auth/login','post','登录',OBJ({'user':REF('Actor')}),OBJ({'username':S(),'password':S()}),True,False)
add('/auth/register','post','注册普通用户',OBJ({'user':REF('Actor')}),OBJ({'username':S(),'password':S(),'displayName':S()}),True,False)
add('/auth/logout','post','退出并关闭会话推送',REF('Success'),idem=False)
add('/profile','patch','更新本人昵称',OBJ({'user':REF('Actor')}),OBJ({'displayName':S()}))
add('/products','get','已审核商品查询',OBJ({'items':ARRAY(REF('Product')),'total':N(),'page':N(),'pageSize':N(),'serverTime':S(format='date-time')}),public=True)
paths['/products']['get']['description'] = '登录后按服务端会话排除本人发布的商品；游客查询全部公开商品。过滤在分页及总数计算前完成，关键词和分类遵循同一规则。本人商品通过 /my/products 管理。'
paths['/products']['get']['parameters'] += [{'name':n,'in':'query','schema':t} for n,t in [('keyword',S()),('category',S()),('page',N(minimum=1)),('pageSize',N(minimum=1,maximum=100))]]
add('/products','post','发布商品并等待审核',REF('Product'),REF('ProductInput'))
add('/products/{id}','get','查询商品详情',REF('Product'),public=True)
add('/products/{id}','patch','修改商品或上下架，校验版本',REF('Product'),{'oneOf':[REF('ProductInput'),OBJ({'saleStatus':S(enum=['AVAILABLE','OFF_SHELF']),'version':N()})]})
add('/my/products','get','本人商品',items('Product'))
add('/orders','post','按后端价格直接下单',REF('Order'),OBJ({'productId':S()}))
add('/orders','get','本人买入或卖出订单',items('Order'))
paths['/orders']['get']['parameters'].append({'name':'side','in':'query','schema':S(enum=['buying','selling'])})
add('/orders/{id}','get','查询授权订单及期限',REF('Order'))
for action in ['payment','cancel','receipt']:add('/orders/{id}/'+action,'post',{'payment':'模拟付款','cancel':'取消待付款订单','receipt':'确认收货结算'}[action],REF('Order'),OBJ({}))
for action in ['shipment','return-shipment']:add('/orders/{id}/'+action,'post','提交发货或退货凭证',REF('Order'),REF('ShipInput'))
add('/cases','get','本人或客服可访问售后单',items('Case'))
add('/cases','post','提交退款或投诉',REF('Case'),OBJ({'orderId':S(),'kind':S(enum=['REFUND','COMPLAINT']),'reason':S()}))
add('/cases/{id}/actions','post','审核、申诉、客服裁决或退货验收',REF('Case'),OBJ({'action':S(enum=['REFUND','REJECT','APPEAL','WITHDRAW','APPROVE_RETURN','ACCEPT_RETURN','DISPUTE','MORE_INFO']),'reason':S()}))
add('/moderation/products','get','管理员待审核商品',items('Product'))
add('/moderation/products/{id}','post','管理员审核商品',REF('Product'),OBJ({'decision':S(enum=['APPROVED','REJECTED']),'reason':S(),'version':N()}))
add('/reports','get','举报记录，管理员可查看全部',items('Report'))
add('/reports','post','举报商品',OBJ({'id':S(),'status':S()}),OBJ({'productId':S(),'reason':S()}))
add('/reports/{id}/resolve','post','管理员处理举报',OBJ({'id':S(),'status':S(),'result':S()}),OBJ({'result':S()}))
add('/notifications','get','本人最近100条通知',items('Notification'))
add('/notifications/{id}/read','post','本人通知标记已读',REF('Success'))
add('/demo/advance','post','仅演示管理员推进时间',OBJ({'serverTime':S(),'ready':B}),OBJ({'millis':N(minimum=1,maximum=31536000000)}))
add('/demo/reset','post','仅演示管理员重置演示业务数据',REF('Success'),OBJ({'confirmation':S(enum=['RESET'])}))
add('/files','post','上传临时图片',REF('Upload'),idem=False)
paths['/files']['post']['requestBody']={'required':True,'content':{'multipart/form-data':{'schema':OBJ({'purpose':S(enum=['PRODUCT','GOODS','PACKAGING','RETURN_GOODS','RETURN_PACKAGING']),'file':S(format='binary')})}}}
add('/files/{id}','get','读取有权限访问的图片',S(format='binary'),public=True)
paths['/files/{id}']['get']['responses']['200']={'description':'图片原件','content':{mime:{'schema':S(format='binary')} for mime in ['image/jpeg','image/png','image/webp']}}
add('/events','get','仅本人业务变化提示；重连后查询最新数据',OBJ({'aggregateId':S(),'type':S()}))
paths['/events']['get']['responses']['200']={'description':'ready / changed 命名事件；无历史重放','content':{'text/event-stream':{'schema':S()}}}
doc={'openapi':'3.1.0','info':{'title':'校园集市协作接口','version':'1.0.0','description':'M01/M02实现及M03-M08演示契约。金额整数分；时间UTC ISO8601。认证与临时上传通过各自会话/文件标识处理，其余业务修改采用Idempotency-Key。'},'servers':[{'url':'/api/v1'}],'paths':paths,'components':{'securitySchemes':{'session':{'type':'apiKey','in':'cookie','name':'JSESSIONID'}},'schemas':schemas}}
Path('contracts/openapi.yaml').write_text(json.dumps(doc,ensure_ascii=False,indent=2)+'\n')
