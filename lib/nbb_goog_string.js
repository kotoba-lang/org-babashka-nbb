import { $APP, shadow$provide } from "./nbb_core.js";
const shadow_esm_import = function(x) { return import(x) };
var IYa;$APP.l4=function(a,b){const c=Array.prototype.slice.call(arguments),d=c.shift();if(typeof d=="undefined")throw Error("[goog.string.format] Template required");return d.replace(/%([0\- \+]*)(\d+)?(\.(\d+))?([%sfdiu])/g,function(e,f,h,m,r,u,x,z){if(u=="%")return"%";const B=c.shift();if(typeof B=="undefined")throw Error("[goog.string.format] Not enough arguments");arguments[0]=B;return $APP.l4.be[u].apply(null,arguments)})};IYa=new $APP.l(null,"format","format",333606761,null);/*

 Copyright The Closure Library Authors.
 SPDX-License-Identifier: Apache-2.0
*/
$APP.l4.be={};$APP.l4.be.s=function(a,b,c){return isNaN(c)||c==""||a.length>=Number(c)?a:a=b.indexOf("-",0)>-1?a+(0,$APP.BB)(" ",Number(c)-a.length):(0,$APP.BB)(" ",Number(c)-a.length)+a};
$APP.l4.be.f=function(a,b,c,d,e){d=a.toString();isNaN(e)||e==""||(d=parseFloat(a).toFixed(e));let f;f=Number(a)<0?"-":b.indexOf("+")>=0?"+":b.indexOf(" ")>=0?" ":"";Number(a)>=0&&(d=f+d);if(isNaN(c)||d.length>=Number(c))return d;d=isNaN(e)?Math.abs(Number(a)).toString():Math.abs(Number(a)).toFixed(e);a=Number(c)-d.length-f.length;b.indexOf("-",0)>=0?d=f+d+(0,$APP.BB)(" ",a):(b=b.indexOf("0",0)>=0?"0":" ",d=f+(0,$APP.BB)(b,a)+d);return d};
$APP.l4.be.d=function(a,b,c,d,e,f,h,m){return $APP.l4.be.f(parseInt(a,10),b,c,d,0,f,h,m)};$APP.l4.be.i=$APP.l4.be.d;$APP.l4.be.u=$APP.l4.be.d;$APP.GW.j($APP.$J,null);$APP.Dz(new $APP.F(null,2,[$APP.Hy,new $APP.F(null,1,[$APP.SG,{format:$APP.l4}],null),$APP.xt,new $APP.F(null,1,[$APP.SG,new $APP.F(null,1,[IYa,$APP.l4],null)],null)],null));