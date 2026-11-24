const item={vod_id:"1",vod_name:"Fixture"},listing={list:[item]};var __spider = {
 async init(ext){this.ext = await Promise.resolve(ext);},
 async home(){if(this.ext !== "fixture") throw Error("init"); return {class:[{type_id:"1",type_name:"Videos"}],...listing};},
 async category(id,page,filter,ext){if(typeof ext !== "object") throw Error("filters");return listing;},
 async search(){const response = await req("./config.json"); if(response.code !== 200) throw Error("http");return listing;},
 async detailContent(ids){if(!Array.isArray(ids)||ids[0] !== "1") throw Error("ids");return {list:[{...item,vod_play_from:"video",vod_play_url:"Episode$http://10.0.2.2:8767/video.mp4"}]};},
 async play(flag,id){return Promise.resolve(id).then(url => ({parse:0,url}));}
};
