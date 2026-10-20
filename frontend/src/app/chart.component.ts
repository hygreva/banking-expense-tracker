import { Component,Input,ViewChild,ElementRef,AfterViewInit,OnChanges,OnDestroy } from '@angular/core';
import { Chart,registerables,ChartType,ChartData } from 'chart.js';
Chart.register(...registerables);
@Component({selector:'app-chart',standalone:true,template:'<div class="chart"><canvas #canvas role="img" [attr.aria-label]="label"></canvas></div>',styles:['.chart{height:270px;position:relative}']})
export class ChartComponent implements AfterViewInit,OnChanges,OnDestroy {
 @ViewChild('canvas') canvas?:ElementRef<HTMLCanvasElement>;@Input() type:ChartType='bar';@Input() label='Financial chart';@Input() data:ChartData={labels:[],datasets:[]};private chart?:Chart;
 ngAfterViewInit(){this.render();}ngOnChanges(){this.render();}ngOnDestroy(){this.chart?.destroy();}
 private render(){if(!this.canvas)return;this.chart?.destroy();this.chart=new Chart(this.canvas.nativeElement,{type:this.type,data:this.data,options:{responsive:true,maintainAspectRatio:false,plugins:{legend:{position:'bottom'}}}});}
}
