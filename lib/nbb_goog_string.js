import { $APP, shadow$provide } from "./nbb_core.js";
const shadow_esm_import = function(x) { return import(x) };
var zNa;$APP.R6=function(a,b){const c=Array.prototype.slice.call(arguments),d=c.shift();if(typeof d=="undefined")throw Error("[goog.string.format] Template required");return d.replace(/%([0\- \+]*)(\d+)?(\.(\d+))?([%sfdiu])/g,function(e,f,h,k,p,t,w,n){if(t=="%")return"%";const B=c.shift();if(typeof B=="undefined")throw Error("[goog.string.format] Not enough arguments");arguments[0]=B;return $APP.R6.Dd[t].apply(null,arguments)})};zNa=new $APP.m(null,"format","format",333606761,null);/*

 Copyright The Closure Library Authors.
 SPDX-License-Identifier: Apache-2.0
*/
$APP.R6.Dd={};$APP.R6.Dd.s=function(a,b,c){return isNaN(c)||c==""||a.length>=Number(c)?a:a=b.indexOf("-",0)>-1?a+(0,$APP.Az)(" ",Number(c)-a.length):(0,$APP.Az)(" ",Number(c)-a.length)+a};
$APP.R6.Dd.f=function(a,b,c,d,e){d=a.toString();isNaN(e)||e==""||(d=parseFloat(a).toFixed(e));let f;f=Number(a)<0?"-":b.indexOf("+")>=0?"+":b.indexOf(" ")>=0?" ":"";Number(a)>=0&&(d=f+d);if(isNaN(c)||d.length>=Number(c))return d;d=isNaN(e)?Math.abs(Number(a)).toString():Math.abs(Number(a)).toFixed(e);a=Number(c)-d.length-f.length;b.indexOf("-",0)>=0?d=f+d+(0,$APP.Az)(" ",a):(b=b.indexOf("0",0)>=0?"0":" ",d=f+(0,$APP.Az)(b,a)+d);return d};
$APP.R6.Dd.d=function(a,b,c,d,e,f,h,k){return $APP.R6.Dd.f(parseInt(a,10),b,c,d,0,f,h,k)};$APP.R6.Dd.i=$APP.R6.Dd.d;$APP.R6.Dd.u=$APP.R6.Dd.d;$APP.LU.h($APP.sH,null);$APP.Gx(new $APP.E(null,2,[$APP.Mw,new $APP.E(null,1,[$APP.vE,{format:$APP.R6}],null),$APP.Es,new $APP.E(null,1,[$APP.vE,new $APP.E(null,1,[zNa,$APP.R6],null)],null)],null));