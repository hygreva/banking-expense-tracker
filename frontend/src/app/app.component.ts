import { Component,inject,OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';import { FormsModule } from '@angular/forms';import { ChartData } from 'chart.js';
import { Api } from './api.service';import { ChartComponent } from './chart.component';import { minorUnits } from './money';import * as M from './models';
@Component({selector:'app-root',standalone:true,imports:[CommonModule,FormsModule,ChartComponent],templateUrl:'./app.component.html'})
export class AppComponent implements OnInit {
 api=inject(Api);user?:M.User;email='alice@demo.local';password='';tab='Overview';busy=false;error='';notice='';
 accounts:M.Account[]=[];categories:M.Category[]=[];beneficiaries:M.Beneficiary[]=[];activities:M.Activity[]=[];budgets:M.Budget[]=[];cases:M.FraudCase[]=[];notifications:{message:string;createdAt:string}[]=[];
 report?:M.Report;expenseData:ChartData={labels:[],datasets:[]};flowData:ChartData={labels:[],datasets:[]};
 month=new Date().toISOString().slice(0,7);from=this.month+'-01';to=this.nextMonth(this.month);historyAccount='';entries:M.Entry[]=[];cursor='';
 accountName='';accountKind='SAVINGS';recipientRef='';beneficiaryAlias='';categoryName='';categoryKind='EXPENSE';
 source='';beneficiary='';amount='';memo='';key='';confirmed=false;transferResult?:M.TransferResult;
 activityId='';activityVersion?:number;activityCategory='';activityKind='EXPENSE';activityAmount='';activityDate=new Date().toISOString().slice(0,10);activityNote='';
 budgetCategory='';budgetAmount='';reviewReason='';
 get customer(){return this.user?.roles.includes('CUSTOMER');}get reviewer(){return this.user?.roles.includes('FRAUD_REVIEWER');}
 get tabs(){return this.customer?['Overview','Accounts','Transfer','Expenses','Budgets','Alerts']:['Review'];}
 get activityCategories(){return this.categories.filter(c=>c.kind===this.activityKind);}
 get expenseCategories(){return this.categories.filter(c=>c.kind==='EXPENSE');}
 nextMonth(s:string){const [y,m]=s.split('-').map(Number);return new Date(Date.UTC(y,m,1)).toISOString().slice(0,10);}
 money(n:number){return new Intl.NumberFormat('en-US',{style:'currency',currency:'USD'}).format(n/100);}
 ngOnInit(){void this.run(async()=>{try{this.user=await this.api.get<M.User>('/auth/me');await this.api.csrf();await this.load();}catch{this.user=undefined;}});}
 async run(action:()=>Promise<void>){if(this.busy)return;this.busy=true;this.error='';this.notice='';try{await action();}catch(e){this.error=this.api.message(e);}finally{this.busy=false;}}
 login(){void this.run(async()=>{await this.api.login(this.email,this.password);this.password='';this.user=await this.api.get<M.User>('/auth/me');await this.load();});}
 logout(){void this.run(async()=>{await this.api.logout();this.user=undefined;this.resetTransfer();this.accounts=[];this.activities=[];this.report=undefined;});}
 async load(){
  if(this.customer){[this.accounts,this.categories,this.beneficiaries]=await Promise.all([this.api.get<M.Account[]>('/accounts'),this.api.get<M.Category[]>('/categories'),this.api.get<M.Beneficiary[]>('/beneficiaries')]);await this.loadReports();this.notifications=await this.api.get('/notifications');if(!this.tabs.includes(this.tab))this.tab='Overview';}
  else if(this.reviewer){this.tab='Review';this.cases=await this.api.get('/fraud-cases');}
 }
 async loadReports(){const q='?from='+this.from+'&to='+this.to;[this.report,this.activities,this.budgets]=await Promise.all([this.api.get<M.Report>('/reports/summary'+q),this.api.get<M.Activity[]>('/manual-activities'+q),this.api.get<M.Budget[]>('/budgets/'+this.month)]);
  this.expenseData={labels:this.report.spendingByCategory.map(x=>x.label),datasets:[{data:this.report.spendingByCategory.map(x=>x.amountMinor/100),backgroundColor:['#0c756f','#dc9f41','#7187aa','#b9819b','#72906c']}]};
  this.flowData={labels:this.report.cashFlowByMonth.map(x=>x.month),datasets:[{label:'Income USD',data:this.report.cashFlowByMonth.map(x=>x.incomeMinor/100),backgroundColor:'#0c756f'},{label:'Expenses USD',data:this.report.cashFlowByMonth.map(x=>x.expenseMinor/100),backgroundColor:'#dc9f41'}]};
 }
 refresh(){void this.run(()=>this.load());}
 monthChange(){this.from=this.month+'-01';this.to=this.nextMonth(this.month);void this.run(()=>this.loadReports());}
 createAccount(){void this.run(async()=>{await this.api.mutate('POST','/accounts',{displayName:this.accountName,kind:this.accountKind});this.accountName='';await this.load();this.notice='Account created with zero balance.';});}
 close(a:M.Account){void this.run(async()=>{await this.api.mutate('POST',`/accounts/${a.id}/close`);await this.load();});}
 addBeneficiary(){void this.run(async()=>{await this.api.mutate('POST','/beneficiaries',{recipientRef:this.recipientRef,alias:this.beneficiaryAlias});this.recipientRef='';this.beneficiaryAlias='';await this.load();this.notice='Recipient saved.';});}
 history(more=false){void this.run(async()=>{if(!more)this.entries=[];const result=await this.api.get<{items:M.Entry[];nextCursor:string}>(`/accounts/${this.historyAccount}/transactions?limit=25`+(more&&this.cursor?'&cursor='+encodeURIComponent(this.cursor):''));this.entries=[...this.entries,...result.items];this.cursor=result.nextCursor;});}
 resetIntent(){this.key='';this.confirmed=false;this.transferResult=undefined;}
 resetTransfer(){this.source='';this.beneficiary='';this.amount='';this.memo='';this.resetIntent();}
 prepare(){try{minorUnits(this.amount);if(!this.source||!this.beneficiary)throw new Error('Select source account and recipient.');this.confirmed=true;this.error='';}catch(e){this.error=this.api.message(e);}}
 send(){void this.run(async()=>{if(!this.key)this.key=crypto.randomUUID();const payload={sourceAccountId:this.source,beneficiaryId:this.beneficiary,amountMinor:minorUnits(this.amount),currency:'USD',memo:this.memo};
  try{this.transferResult=await this.api.mutate<M.TransferResult>('POST','/transfers',payload,{'Idempotency-Key':this.key});}catch(e:any){if(e.status===422&&e.error?.status==='REJECTED')this.transferResult=e.error;else throw e;}
  this.confirmed=false;await this.load();});}
 status(){if(this.transferResult)void this.run(async()=>{this.transferResult=await this.api.get('/transfers/'+this.transferResult!.id);});}
 saveActivity(){void this.run(async()=>{const body={categoryId:this.activityCategory,kind:this.activityKind,amountMinor:minorUnits(this.activityAmount),occurredAt:this.activityDate+'T16:00:00Z',note:this.activityNote,version:this.activityVersion};await this.api.mutate(this.activityId?'PATCH':'POST','/manual-activities'+(this.activityId?'/'+this.activityId:''),body);this.clearActivity();await this.load();this.notice='Expense observation saved. Account balances are unchanged.';});}
 edit(a:M.Activity){this.activityId=a.id;this.activityVersion=a.version;this.activityCategory=a.categoryId;this.activityKind=a.kind;this.activityAmount=(a.amountMinor/100).toFixed(2);this.activityDate=a.occurredAt.slice(0,10);this.activityNote=a.note;}
 clearActivity(){this.activityId='';this.activityVersion=undefined;this.activityAmount='';this.activityNote='';}
 delete(a:M.Activity){void this.run(async()=>{await this.api.mutate('DELETE','/manual-activities/'+a.id,null,{'If-Match':String(a.version)});await this.load();});}
 addCategory(){void this.run(async()=>{await this.api.mutate('POST','/categories',{name:this.categoryName,kind:this.categoryKind});this.categoryName='';await this.load();});}
 selectBudget(){const b=this.budgets.find(b=>b.categoryId===this.budgetCategory);this.budgetAmount=b?(b.limitMinor/100).toFixed(2):'';}
 saveBudget(){void this.run(async()=>{const b=this.budgets.find(x=>x.categoryId===this.budgetCategory);await this.api.mutate('PUT',`/budgets/${this.month}/${this.budgetCategory}`,{limitMinor:minorUnits(this.budgetAmount),version:b?.version??null});await this.load();this.notice='Budget saved.';});}
 decide(c:M.FraudCase,decision:string){void this.run(async()=>{await this.api.mutate('POST',`/fraud-cases/${c.id}/decision`,{decision,reason:this.reviewReason,version:c.version});this.reviewReason='';await this.load();this.notice='Decision recorded.';});}
 get csvUrl(){return '/api/v1/reports/export?from='+this.from+'&to='+this.to;}
}
