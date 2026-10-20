import { Injectable,inject } from '@angular/core';
import { HttpClient,HttpErrorResponse,HttpHeaders } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
@Injectable({providedIn:'root'})
export class Api {
 private http=inject(HttpClient);private token='';private header='X-CSRF-TOKEN';
 get<T>(path:string):Promise<T>{return firstValueFrom(this.http.get<T>('/api/v1'+path));}
 async csrf(){const c=await this.get<{token:string;headerName:string}>('/auth/csrf');this.token=c.token;this.header=c.headerName;}
 async login(email:string,password:string){await this.csrf();const data=new URLSearchParams({email,password});await firstValueFrom(this.http.post('/api/v1/auth/login',data.toString(),{headers:new HttpHeaders({[this.header]:this.token,'Content-Type':'application/x-www-form-urlencoded'})}));await this.csrf();}
 async mutate<T>(method:string,path:string,body:unknown=null,extra:Record<string,string>={}):Promise<T>{if(!this.token)await this.csrf();return firstValueFrom(this.http.request<T>(method,'/api/v1'+path,{body,headers:new HttpHeaders({[this.header]:this.token,...extra})}));}
 async logout(){await this.mutate('POST','/auth/logout');this.token='';}
 message(e:unknown):string {if(e instanceof HttpErrorResponse){if(e.status===401)return 'Your session has expired. Sign in again.';if(e.status===0)return 'Connection lost. Retry a transfer using the unchanged request.';return e.error?.code || `Request failed (${e.status}).`;}return e instanceof Error?e.message:'Unable to complete request.';}
}
