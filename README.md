# cliente-http

Um cliente HTTP/1.1 escrito do zero em Java 21 sobre `Socket`: cabeçalhos,
corpo com `Content-Length`, corpo em pedaços, gzip, redirecionamento, prazos e
TLS. Sem OkHttp, sem Apache HttpClient, sem `HttpURLConnection`.

Os juízes dos testes são o `HttpServer` e o `HttpClient` que já vêm no JDK.

```
$ obter https://api.github.com/repos/condeDeveloper/bytecode
200 OK (5599 bytes)
  Content-Type:     application/json; charset=utf-8
  Content-Encoding: gzip
  charset lido:     UTF-8
  "description":"Gerador de arquivos .class do zero em Java: pool de constantes…

$ HEAD https://api.github.com/  ->  200, corpo de 0 bytes
```

## Por que existe

HTTP é texto sobre TCP e a requisição inteira cabe em quatro linhas:

```
GET /caminho HTTP/1.1
Host: exemplo.com
Connection: close

```

Parece simples — e é, até a primeira resposta real chegar. As cinco armadilhas
abaixo pegam todo mundo que escreve isso à mão.

### 1. Não envolva o soquete num `BufferedReader`

Esta é **a** armadilha, e ela está na primeira linha do leitor.

O instinto é usar um `BufferedReader` para ler os cabeçalhos linha a linha. Só
que ele decodifica e **lê adiante**, engolindo bytes do corpo que nunca mais
voltam. O corpo então chega curto, ou vazio, ou embaralhado — e o sintoma não
tem relação nenhuma com a causa.

Aqui os cabeçalhos são lidos **byte a byte** do mesmo fluxo que o corpo vai
usar, procurando o CRLF na mão. Feio? É. Correto? Também.

### 2. `read` devolve menos do que você pediu

E quase sempre devolve. Um `read(bytes)` num soquete traz o que já chegou, não
o que vai chegar. Sem um laço que insista até completar o `Content-Length`, um
corpo de 100 KB chega pela metade — e só em rede de verdade, nunca no teste
local contra `localhost`.

Há um teste que pede 100 KB exatamente por isso.

### 3. O corpo em pedaços existe porque o servidor não sabe o tamanho

```
2a\r\n
<42 bytes>\r\n
0\r\n
\r\n
```

É o que permite responder algo gerado na hora sem segurar tudo em memória
primeiro. Dois detalhes que quebram implementações apressadas:

- O tamanho pode vir com extensões: `1a;nome=valor`. Quem não corta no ponto e
  vírgula lê um hexadecimal inválido.
- Depois do pedaço zero vêm os *trailers*, e eles precisam ser consumidos.

E o tamanho é em **bytes**. Um teste aqui parte `ação` byte a byte em quatro
pedaços de 1: quem decodifica pedaço por pedaço na chegada estraga o
caractere, quem remonta os bytes e decodifica no fim, não.

### 4. O padrão do HTTP é latin-1, não UTF-8

Surpreende todo mundo. Quando o servidor não declara `charset`, a
especificação manda supor ISO-8859-1. Decodificar em UTF-8 por reflexo é como
se perde acento em resposta de sistema antigo.

Por isso o corpo fica como **bytes** e só vira texto quando alguém pede, no
charset que o servidor declarou.

### 5. Cabeçalho repetido não é erro

`Set-Cookie` vem várias vezes, de propósito. Um `Map<String, String>` perde
todos menos o último. E o nome não diferencia maiúscula de minúscula:
`Content-Type` e `content-type` são o mesmo cabeçalho, e guardar num mapa
comum faz o cliente ignorar a resposta de metade dos servidores do mundo.

### Bônus: o `Host` e o TLS

O `Host` é obrigatório no HTTP/1.1 e foi a mudança que permitiu hospedar mil
sites num IP só — sem ele o servidor não tem como saber qual você quer, porque
o TCP só carrega o endereço.

E, no TLS, esta linha não é opcional:

```java
parametros.setEndpointIdentificationAlgorithm("HTTPS");
```

Ela **não** vem ligada num `SSLSocket`, e é a pegadinha mais cara do
`java.net`: sem ela o JDK confere se o certificado é confiável, mas não se ele
foi emitido para esta máquina. Um certificado válido de outro domínio passa.

## A API

```java
ClienteHttp cliente = ClienteHttp.construtor()
    .prazoDeConexao(10_000)
    .prazoDeLeitura(30_000)
    .maximoDeRedirecionamentos(5)
    .aceitarCompressao(true)
    .corpoMaximo(32 * 1024 * 1024)
    .montar();

Resposta resposta = cliente.obter("https://exemplo.com/api");

resposta.status();                    // 200
resposta.ok();                        // true
resposta.texto();                     // no charset que o servidor declarou
resposta.corpo();                     // os bytes crus
resposta.cabecalhos().todos("Set-Cookie");
resposta.redirecionamentos();         // o caminho, quando houve

cliente.postar(url, "{\"a\":1}", "application/json");
cliente.enviar(Requisicao.cabeca(url));
cliente.enviar(Requisicao.obter(url).com("Authorization", "Bearer …"));
```

Erro do servidor volta como **resposta**, não como exceção: um 500 é uma
resposta HTTP perfeitamente válida, e quem chama decide o que fazer.

## Estrutura

```
mensagem/Cabecalhos.java        nome sem caixa, valor repetido, ordem preservada
mensagem/LeitorDeResposta.java  status, cabeçalhos, pedaços, gzip
mensagem/Resposta.java          o corpo como bytes e o charset declarado
cliente/Requisicao.java         método, URL, cabeçalhos e corpo
cliente/ClienteHttp.java        soquete, TLS, montagem e redirecionamento
```

## Rodando

```bash
mvn test
```

31 testes. A maior parte roda contra o **`HttpServer` do JDK**, subido no teste
com rotas que respondem 204, 500, gzip, latin-1, vários `Set-Cookie`,
redirecionamento relativo e um laço infinito de propósito.

Dois comparam o resultado com o do **`java.net.http.HttpClient`** do JDK
falando com o mesmo servidor: se as duas implementações divergem, é esta que
está errada.

Cinco usam um `ServerSocket` cru que responde bytes escolhidos à mão, que é o
único jeito de testar corpo em pedaços, resposta sem `Content-Length` e linha
de status malformada.

Java 21.

## Limites conhecidos

- **Uma conexão por requisição.** Manda `Connection: close` e fecha. Não há
  pool nem `keep-alive`, que é o que mais pesa em desempenho num cliente real.
- **Sem HTTP/2 e sem HTTP/3.** Só 1.1 em texto — que é justamente o que dá
  para escrever à mão e ler no `tcpdump`.
- **Carrega o corpo inteiro na memória**, com um teto de 32 MB. Não há resposta
  em fluxo nem download para arquivo.
- **Sem cookies.** `Set-Cookie` é lido e devolvido, mas nada os guarda nem os
  reenvia — inclusive no redirecionamento.
- **Sem autenticação automática**, sem proxy, sem cache, sem repetição.
- **Sem `multipart/form-data`.** O corpo é um vetor de bytes que quem chama
  monta.
- O TLS é o do JDK. Escrever TLS à mão seria outro projeto — e não é um projeto
  que valha escrever para usar de verdade.

## Licença

MIT.
