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
NULL={'type':'null'}
NULLABLE_STRING={'type':['string','null']}
REVIEW_ROLE=S(enum=['BUYER','SELLER'])
PROFILE_FIELDS={
 'displayName':S(minLength=1,maxLength=80), 'avatarId':{'type':['string','null'],'maxLength':36},
 'school':S(maxLength=80), 'campus':S(maxLength=80), 'bio':S(maxLength=300),
 'gender':S(enum=['','MALE','FEMALE','OTHER']),
}
PROFILE_PROPS={'id':S(),**PROFILE_FIELDS,'avatarUrl':NULLABLE_STRING}
REVIEW_INPUT={**OBJ({'stars':N(minimum=1,maximum=5),'comment':S(minLength=1,maxLength=500)}),'additionalProperties':False}
schemas={
 'Actor':OBJ({'id':S(),'username':S(),'displayName':S(),'role':S(enum=['USER','ADMIN','SUPPORT'])}),
 'UserProfile':OBJ(PROFILE_PROPS),
 'CreditSummary':OBJ({'count':N(minimum=0),'average':{'type':['number','null'],'minimum':1,'maximum':5},
  'grade':S(enum=['暂无评价','优秀','良好','一般','待提升']),'distribution':OBJ({str(n):N(minimum=0) for n in range(1,6)})}),
 'ProfileContext':OBJ({'type':S(enum=['product','order','report','case']),'id':S(),'title':S(),
  'details':ARRAY(OBJ({'label':S(),'value':S()})),'links':ARRAY(OBJ({'label':S(),'path':S()}))}),
 'UserProfileView':OBJ({**PROFILE_PROPS,'buyerCredit':REF('CreditSummary'),'sellerCredit':REF('CreditSummary'),
  'context':{'anyOf':[REF('ProfileContext'),NULL]}}),
 'PublicReview':OBJ({'id':S(),'reviewerName':S(),'reviewerRole':REVIEW_ROLE,'subjectRole':REVIEW_ROLE,
  'stars':N(minimum=1,maximum=5),'comment':S(),'publishedAt':S(format='date-time'),'productTitle':S()}),
 'OwnReview':OBJ({'id':S(),'stars':N(minimum=1,maximum=5),'comment':S(),'submittedAt':S(format='date-time'),'publishedAt':DATE}),
 'ReviewSnapshot':OBJ({'eligible':B,'reason':S(),'deadline':DATE,'serverTime':S(format='date-time'),
  'ownReview':{'anyOf':[REF('OwnReview'),NULL]},'items':ARRAY(REF('PublicReview'))}),
 'Product':OBJ({'id':S(),'sellerId':S(),'title':S(),'description':S(),'category':S(),'price':N(minimum=1),'images':ARRAY(S()),'auditStatus':S(),'saleStatus':S(),'version':N(),'createdAt':DATE,'sellerName':S(),'purchasable':{'type':'boolean','description':'M05 列表项的可下单口径；仅商品列表返回，详情与订单内嵌对象不保证存在'}},['id','sellerId','title','description','category','price','images','auditStatus','saleStatus','version','createdAt']),
 'Case':OBJ({'id':S(),'orderId':S(),'buyerId':S(),'kind':S(),'reason':S(),'status':S(),'decision':{'type':['string','null']},'decisionReason':{'type':['string','null']},'deadline':DATE,'generation':N(),'createdAt':DATE,'version':N()}),
 'CaseMessage':OBJ({'id':S(),'caseId':S(),'senderId':S(),'role':S(enum=['BUYER','SELLER','SUPPORT']),'content':S(),'createdAt':DATE}),
 'Shipment':OBJ({'id':S(),'orderId':S(),'direction':S(),'trackingNumber':S(),'evidence':OBJ({'goods':ARRAY(S()),'packaging':ARRAY(S())}),'status':S(),'shippedAt':DATE,'dueAt':DATE,'deliveredAt':DATE,'generation':N()}),
 'Order':OBJ({'id':S(),'productId':S(),'buyerId':S(),'sellerId':S(),'buyerName':S(description='买家当前昵称'),'sellerName':S(description='卖家当前昵称'),'amount':N(),'status':S(),'closeReason':{'type':['string','null']},'createdAt':DATE,'payDeadline':DATE,'receiptDeadline':DATE,'receiptRemaining':{'type':['integer','null']},'receiptPaused':B,'shippingBlocked':B,'settlementBlocked':B,'paymentGeneration':N(),'receiptGeneration':N(),'version':N(),'product':REF('Product'),'serverTime':S(format='date-time'),'fundsStatus':S(),'shipments':ARRAY(REF('Shipment')),'cases':ARRAY(REF('Case')),'allowedActions':ARRAY(S())}),
 'Notification':OBJ({'id':S(),'recipientId':S(),'aggregateId':S(),'message':S(),'createdAt':DATE,'isRead':B}),
 'NotificationSnapshot':OBJ({'items':ARRAY(REF('Notification')),'unreadCount':N(minimum=0),'latestId':{'type':['string','null']}}),
 'Report':OBJ({'id':S(),'productId':S(),'reporterId':S(),'sellerId':{**NULLABLE_STRING,'description':'仅管理员可见'},'reason':S(),'status':S(),'result':NULLABLE_STRING,'createdAt':DATE,'duplicated':{'type':'boolean','description':'M06 同一用户重复举报同一商品时返回 true，并复用原举报编号'},'decision':{'type':['string','null'],'description':'M06 处置结论：VIOLATION / DISMISSED / null'},'productTakedown':{'type':'boolean','description':'M06 处置是否触发商品下架'}},['id','productId','reporterId','reason','status','result','createdAt']),
 'Error':OBJ({'code':S(),'message':S()}),
 'Session':OBJ({'user':{'anyOf':[REF('Actor'),{'type':'null'}]},'balance':{'type':['integer','null']},'csrfToken':S(),'demo':B}),
 'Success':OBJ({'success':B}),
 'Upload':OBJ({'id':S(),'url':S(),'purpose':S()}),
 'ProductInput':OBJ({'title':S(minLength=1,maxLength=120),'description':S(minLength=1,maxLength=5000),'category':S(),'price':N(minimum=1,maximum=100000000),'images':ARRAY(S()),'version':N()},['title','description','category','price','images']),
 'ShipInput':OBJ({'trackingNumber':S(minLength=1,maxLength=100),'goods':ARRAY(S()),'packaging':ARRAY(S())}),
}
paths={}
def add(path,method,summary,output,body=None,public=False,idem=True,not_found=False):
 p=[]
 for part in path.split('/'):
  if part.startswith('{'):p.append({'name':part[1:-1],'in':'path','required':True,'schema':S()})
 if method not in ('get',):
  p.append({'name':'X-CSRF-TOKEN','in':'header','required':True,'schema':S()})
  if idem:p.append({'name':'Idempotency-Key','in':'header','required':True,'schema':S(minLength=8,maxLength=100)})
 errors=[(400,'输入无效'),(401,'未登录'),(403,'无权限或CSRF失败'),(409,'状态、期限或请求冲突'),(503,'恢复中或数据库暂时不可用')]
 if not_found:errors.insert(3,(404,'业务记录不存在'))
 spec={'summary':summary,'operationId':method+'_'+path.replace('/','_').replace('{','').replace('}','').replace('-','_'),'security':[] if public else [{'session':[]}],'parameters':p,'responses':{'200':{'description':'成功','content':{'application/json':{'schema':output}}},**{str(code):{'description':desc,'content':{'application/json':{'schema':REF('Error')}}} for code,desc in errors}}}
 if body is not None:spec['requestBody']={'required':True,'content':{'application/json':{'schema':body}}}
 paths.setdefault(path,{})[method]=spec
items=lambda name:OBJ({'items':ARRAY(REF(name))})
add('/session','get','当前会话与CSRF令牌',REF('Session'),public=True)
add('/auth/login','post','登录',OBJ({'user':REF('Actor')}),OBJ({'username':S(),'password':S()}),True,False)
add('/auth/register','post','注册普通用户',OBJ({'user':REF('Actor')}),OBJ({'username':S(),'password':S(),'displayName':S()}),True,False)
add('/auth/logout','post','退出并关闭会话推送',REF('Success'),idem=False)
add('/profile','get','查看本人资料',REF('UserProfile'))
add('/profile','patch','更新本人资料，岗位账号仅可修改昵称和头像',OBJ({'user':REF('Actor'),'profile':REF('UserProfile')}),
    {**OBJ(PROFILE_FIELDS,[]),'minProperties':1,'additionalProperties':False})
def profile_get(resource,summary,output,filters=()):
 path='/users/{id}/'+resource
 add(path,'get',summary,output,not_found=True)
 paths[path]['get']['description']='查看本人资料无需上下文。其他普通用户资料需有权访问对应商品、订单、举报或售后单，且目标用户与业务记录相关。'
 paths[path]['get']['parameters'] += [{'name':n,'in':'query','schema':t} for n,t in [
     ('contextType',S(enum=['product','order','report','case'])),('contextId',S()),*filters]]
def page(name):
 return OBJ({'items':ARRAY(REF(name)),'total':N(minimum=0),'page':N(minimum=1),'pageSize':N(const=20)})
profile_get('profile','查看授权用户资料及买卖信用',REF('UserProfileView'))
paths['/users/{id}/profile']['get']['description'] += '岗位账号可查看当前记录的业务详情、处理历史和业务链接。'
profile_get('products','查看授权用户的已审核在售和售出商品',page('Product'),
    [('page',N(minimum=1,default=1)),('status',S(enum=['ALL','AVAILABLE','SOLD'],default='ALL'))])
profile_get('reviews','查看授权用户的已公开评价',page('PublicReview'),
    [('page',N(minimum=1,default=1)),('role',S(enum=['ALL','BUYER','SELLER'],default='ALL'))])
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
add('/orders/{id}/reviews','get','查询订单评价资格、本人评价和已公开评价',REF('ReviewSnapshot'),not_found=True)
add('/orders/{id}/reviews','post','在结算后7天内提交一次不可编辑的评价',REF('ReviewSnapshot'),REVIEW_INPUT,not_found=True)
paths['/orders/{id}/reviews']['get']['description']='买卖双方可查看自己的待公开评价。关联售后的客服可查看已公开评价。'
paths['/orders/{id}/reviews']['post']['description']='双方提交后同步公开。期限届满时公开已提交评价。'
for action in ['payment','cancel','receipt']:add('/orders/{id}/'+action,'post',{'payment':'模拟付款','cancel':'取消待付款订单','receipt':'确认收货结算'}[action],REF('Order'),OBJ({}))
for action in ['shipment','return-shipment']:add('/orders/{id}/'+action,'post','提交发货或退货凭证',REF('Order'),REF('ShipInput'))
add('/cases','get','本人或客服可访问售后单',items('Case'))
add('/cases','post','提交退款或投诉',REF('Case'),OBJ({'orderId':S(),'kind':S(enum=['REFUND','COMPLAINT']),'reason':S()}))
add('/cases/{id}/actions','post','审核、申诉、客服裁决或退货验收',REF('Case'),OBJ({'action':S(enum=['REFUND','REJECT','APPEAL','WITHDRAW','APPROVE_RETURN','ACCEPT_RETURN','DISPUTE','MORE_INFO']),'reason':S()}))
add('/cases/{id}/messages','get','查询售后单留言',items('CaseMessage'))
add('/cases/{id}/messages','post','在售后单内留言',REF('CaseMessage'),OBJ({'content':S(minLength=1,maxLength=500)}))
add('/moderation/products','get','管理员待审核商品',items('Product'))
add('/moderation/products/{id}','post','管理员审核商品',REF('Product'),OBJ({'decision':S(enum=['APPROVED','REJECTED']),'reason':S(),'version':N()}))
add('/reports','get','举报记录，管理员可查看全部',items('Report'))
add('/reports','post','举报商品',OBJ({'id':S(),'status':S()}),OBJ({'productId':S(),'reason':S()}))
add('/reports/{id}/resolve','post','管理员处理举报',OBJ({'id':S(),'status':S(),'result':S()}),OBJ({'result':S()}))
add('/notifications','get','本人最近100条通知及全部未读数量',REF('NotificationSnapshot'))
add('/notifications/read-all','post','本人截至指定编号的通知全部标为已读',REF('Success'),OBJ({'throughId':S(pattern='^[1-9][0-9]{0,18}$')}))
add('/notifications/{id}/read','post','本人通知标记已读',REF('Success'))
add('/demo/advance','post','仅演示管理员推进时间',OBJ({'serverTime':S(),'ready':B}),OBJ({'millis':N(minimum=1,maximum=31536000000)}))
add('/demo/reset','post','仅演示管理员重置演示业务数据',REF('Success'),OBJ({'confirmation':S(enum=['RESET'])}))
add('/files','post','上传临时图片',REF('Upload'),idem=False)
paths['/files']['post']['requestBody']={'required':True,'content':{'multipart/form-data':{'schema':OBJ({'purpose':S(enum=['PRODUCT','GOODS','PACKAGING','RETURN_GOODS','RETURN_PACKAGING','AVATAR']),'file':S(format='binary')})}}}
add('/files/{id}','get','读取有权限访问的图片',S(format='binary'),public=True)
paths['/files/{id}']['get']['responses']['200']={'description':'图片原件','content':{mime:{'schema':S(format='binary')} for mime in ['image/jpeg','image/png','image/webp']}}
add('/events','get','仅本人业务变化提示；重连后查询最新数据',OBJ({'aggregateId':S(),'type':S()}))
paths['/events']['get']['responses']['200']={'description':'ready / changed / notifications-read 命名事件；notifications-read仅表示本人已读状态已提交；无历史重放','content':{'text/event-stream':{'schema':S()}}}
doc={'openapi':'3.1.0','info':{'title':'校园集市协作接口','version':'1.0.0','description':'M01/M02实现及M03-M08演示契约。金额整数分；时间UTC ISO8601。认证与临时上传通过各自会话/文件标识处理，其余业务修改采用Idempotency-Key。'},'servers':[{'url':'/api/v1'}],'paths':paths,'components':{'securitySchemes':{'session':{'type':'apiKey','in':'cookie','name':'JSESSIONID'}},'schemas':schemas}}
Path('contracts/openapi.yaml').write_text(json.dumps(doc,ensure_ascii=False,indent=2)+'\n')
